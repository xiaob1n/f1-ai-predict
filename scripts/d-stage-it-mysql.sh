#!/usr/bin/env bash
# ============================================================
# D 阶段 MySQL 集成测试专用容器（Apple container，非 Docker / Testcontainers）。
#
# 用法：
#   scripts/d-stage-it-mysql.sh up       # 启动本次专属 MySQL 8.0，按 001..011 建表，写连接环境文件
#   scripts/d-stage-it-mysql.sh status   # 核实本次容器归属与状态，不输出任何凭据
#   scripts/d-stage-it-mysql.sh env      # 只打印连接环境文件路径
#   scripts/d-stage-it-mysql.sh down     # 核实容器 ID 与 run 标记后停止并删除本次容器
#
# 运行 IT（在 f1aipredict/ 目录）：
#   set -a; . "$(../scripts/d-stage-it-mysql.sh env)"; set +a
#   ./mvnw -Pit verify
#
# 安全边界：
#   - 每次 up 生成随机 run 标记、容器名与凭据；容器带 run 标记标签，down 只删除标签与记录一致的容器，
#     从不使用 prune、--all 或按名称模式批量删除。
#   - 端口只发布到 127.0.0.1，发布失败不退回其他地址；不挂载任何主机目录，数据目录为容器内 tmpfs。
#   - 凭据只写入 0600 文件并经 --env-file / 标准输入传递，不出现在命令参数、标准输出或错误日志中。
#   - 状态目录：F1_IT_STATE_DIR > ${CLAUDE_JOB_DIR}/tmp/d-stage-it-mysql > ${TMPDIR}/f1-d-stage-it-<uid>，
#     均要求属当前用户且权限 0700。不读取服务器 .env 或 application.yaml。
#   - SQL 只执行仓库 sql/ 下固定清单 001..011；出现清单外的编号脚本时拒绝执行，需先人工审阅。
# ============================================================
set -euo pipefail
umask 077

readonly SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
readonly REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
readonly SQL_DIR="${REPO_ROOT}/sql"
readonly IMAGE="${F1_IT_MYSQL_IMAGE:-docker.io/library/mysql:8.0}"
readonly READY_TIMEOUT_SECONDS="${F1_IT_READY_TIMEOUT_SECONDS:-300}"
readonly RUN_LABEL_KEY="com.lbz.f1aipredict.d-stage-it.run"
readonly OWNER_LABEL="com.lbz.f1aipredict.d-stage-it.owner=d-stage-it-mysql.sh"
readonly DB_NAME="f1_ai_predict"
readonly GUARD_DB="f1_it_guard"
readonly APP_USER="f1_it_app"
# 固定执行顺序；新增编号脚本必须先审阅再加入本清单。
readonly SQL_FILES=(
    001_create_database.sql
    002_season_round.sql
    003_question.sql
    004_prediction.sql
    005_answer_scoring.sql
    006_sync.sql
    007_prediction_request_outbox.sql
    008_prediction_outcome.sql
    009_prediction_lock.sql
    010_official_answer_history.sql
    011_scoring_status.sql
)

STATE_DIR=""
STATE_FILE=""
# up 成功标志；EXIT trap 依赖它决定是否清理，必须是全局变量。
UP_OK=0

log() { printf '[d-stage-it] %s\n' "$*" >&2; }
die() { log "错误: $*"; exit 1; }

require_tools() {
    command -v container >/dev/null 2>&1 || die "未找到 Apple container CLI"
    command -v python3 >/dev/null 2>&1 || die "需要 python3 解析 container JSON 输出"
}

# 解析并校验私有状态目录：不得是符号链接，必须属于当前用户且权限为 0700。
resolve_state_dir() {
    local dir
    if [[ -n "${F1_IT_STATE_DIR:-}" ]]; then
        dir="${F1_IT_STATE_DIR}"
    elif [[ -n "${CLAUDE_JOB_DIR:-}" && -d "${CLAUDE_JOB_DIR}/tmp" ]]; then
        dir="${CLAUDE_JOB_DIR}/tmp/d-stage-it-mysql"
    else
        dir="${TMPDIR:-/tmp}"
        dir="${dir%/}/f1-d-stage-it-$(id -u)"
    fi
    if [[ -L "${dir}" ]]; then
        die "状态目录是符号链接，拒绝使用: ${dir}"
    fi
    if [[ ! -d "${dir}" ]]; then
        mkdir -p "${dir}"
        chmod 700 "${dir}"
    fi
    [[ -O "${dir}" ]] || die "状态目录不属于当前用户: ${dir}"
    local mode
    mode="$(stat -f '%Lp' "${dir}" 2>/dev/null || stat -c '%a' "${dir}")"
    [[ "${mode}" == "700" ]] || die "状态目录权限必须为 700（当前 ${mode}）: ${dir}"
    STATE_DIR="${dir}"
    STATE_FILE="${STATE_DIR}/state.env"
}

random_hex() {
    # 只输出到命令替换，不进入任何日志。
    python3 -I -c 'import secrets, sys; print(secrets.token_hex(int(sys.argv[1])))' "$1"
}

pick_loopback_port() {
    python3 -I -c 'import socket
s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
s.bind(("127.0.0.1", 0))
print(s.getsockname()[1])
s.close()'
}

# 读取状态文件（只含非敏感字段），不 source 任意内容。
load_state() {
    [[ -f "${STATE_FILE}" ]] || return 1
    RUN_ID="$(sed -n 's/^RUN_ID=\([0-9a-f]\{32\}\)$/\1/p' "${STATE_FILE}")"
    CONTAINER_ID="$(sed -n 's/^CONTAINER_ID=\(f1-dstage-it-[0-9a-f]\{12\}\)$/\1/p' "${STATE_FILE}")"
    HOST_PORT="$(sed -n 's/^HOST_PORT=\([0-9]\{1,5\}\)$/\1/p' "${STATE_FILE}")"
    [[ -n "${RUN_ID}" && -n "${CONTAINER_ID}" && -n "${HOST_PORT}" ]] || die "状态文件格式无效，拒绝继续: ${STATE_FILE}"
    return 0
}

# 通过 list --all JSON 核实容器归属；输出 MISSING / AMBIGUOUS / FOREIGN / OWNED <state> <publish>。
# container CLI 自身失败时返回非零，调用方据此区分“容器不存在”与“无法查询”。
probe_container() {
    local id="$1" run_id="$2" json
    json="$(container list --all --format json)" || return 1
    python3 -I -c '
import json, sys
want_id, want_run, label_key = sys.argv[1:4]
data = json.load(sys.stdin)
items = data if isinstance(data, list) else []
matches = []
for item in items:
    cfg = item.get("configuration") or {}
    cid = cfg.get("id") or item.get("id")
    if cid == want_id:
        matches.append(item)
if not matches:
    print("MISSING")
    sys.exit(0)
if len(matches) != 1:
    print("AMBIGUOUS")
    sys.exit(0)
item = matches[0]
cfg = item.get("configuration") or {}
labels = cfg.get("labels") or {}
if labels.get(label_key) != want_run:
    print("FOREIGN")
    sys.exit(0)
status = item.get("status")
state = status.get("state") if isinstance(status, dict) else status
ports = cfg.get("publishedPorts")
if ports is None:
    publish = "UNKNOWN"
elif ports and all(str(p.get("hostAddress")) == "127.0.0.1" for p in ports if isinstance(p, dict)):
    publish = "LOOPBACK"
else:
    publish = "NOT_LOOPBACK"
print("OWNED", state or "unknown", publish)
' "${id}" "${run_id}" "${RUN_LABEL_KEY}" <<<"${json}"
}

# 只对已核实归属的容器执行停止与删除；返回 0 表示确认已不存在。
remove_owned_container() {
    local id="$1" run_id="$2" probe
    probe="$(probe_container "${id}" "${run_id}")" || { log "无法查询 container 列表，未执行删除"; return 1; }
    case "${probe}" in
        MISSING)
            log "容器 ${id} 已不存在"
            return 0
            ;;
        OWNED*)
            ;;
        *)
            log "容器 ${id} 归属核实失败（${probe}），拒绝删除"
            return 1
            ;;
    esac
    container stop --time 10 "${id}" >/dev/null 2>&1 || log "停止容器 ${id} 失败，继续尝试删除该已核实容器"
    # 删除前再次核实，防止停止期间被替换。
    probe="$(probe_container "${id}" "${run_id}")" || { log "无法查询 container 列表，未执行删除"; return 1; }
    case "${probe}" in
        MISSING) return 0 ;;
        OWNED*) ;;
        *) log "容器 ${id} 归属二次核实失败（${probe}），拒绝删除"; return 1 ;;
    esac
    if ! container delete --force "${id}" >/dev/null; then
        log "删除容器 ${id} 失败"
        return 1
    fi
    probe="$(probe_container "${id}" "${run_id}")" || { log "删除后无法核实容器状态"; return 1; }
    if [[ "${probe}" != "MISSING" ]]; then
        log "删除后容器 ${id} 仍存在（${probe}）"
        return 1
    fi
    return 0
}

remove_state_files() {
    rm -f "${STATE_DIR}/container.env" "${STATE_DIR}/root-client.env" "${STATE_DIR}/connection.env" "${STATE_FILE}"
}

# 输出容器日志尾部时屏蔽本次凭据（经进程替换传入，避免出现在命令参数中）。
print_log_tail() {
    local id="$1"
    log "容器日志尾部（已屏蔽凭据）："
    container logs -n 40 "${id}" 2>/dev/null \
        | grep -v -F -f <(printf '%s\n' "${ROOT_PASSWORD:-__none__}" "${APP_PASSWORD:-__none__}") >&2 || true
}

check_sql_inventory() {
    local expected actual
    expected="$(printf '%s\n' "${SQL_FILES[@]}" | sort)"
    actual="$(cd "${SQL_DIR}" && ls [0-9][0-9][0-9]_*.sql | sort)"
    if [[ "${expected}" != "${actual}" ]]; then
        die "sql/ 编号脚本与固定清单不一致，需先人工审阅后更新清单。实际：$(echo "${actual}" | tr '\n' ' ')"
    fi
}

mysql_root_exec() {
    # 参数：可选 --database=<db>；SQL 从标准输入读取。密码经 --env-file 注入 MYSQL_PWD。
    container exec -i --env-file "${STATE_DIR}/root-client.env" "${CONTAINER_ID}" \
        mysql --protocol=TCP -h127.0.0.1 -uroot --default-character-set=utf8mb4 "$@"
}

cmd_up() {
    require_tools
    resolve_state_dir
    check_sql_inventory
    if [[ -f "${STATE_FILE}" ]]; then
        die "已有运行记录 ${STATE_FILE}，请先执行 down"
    fi
    container list --quiet >/dev/null || die "container 服务不可用（可由用户执行 container system start）"

    RUN_ID="$(random_hex 16)"
    CONTAINER_ID="f1-dstage-it-${RUN_ID:0:12}"
    HOST_PORT="${F1_IT_HOST_PORT:-$(pick_loopback_port)}"
    [[ "${HOST_PORT}" =~ ^[0-9]{1,5}$ ]] || die "F1_IT_HOST_PORT 无效"
    ROOT_PASSWORD="$(random_hex 24)"
    APP_PASSWORD="$(random_hex 24)"

    # 先落状态再创建容器，使任何中途失败都能按记录核实归属后清理。
    printf 'RUN_ID=%s\nCONTAINER_ID=%s\nHOST_PORT=%s\n' "${RUN_ID}" "${CONTAINER_ID}" "${HOST_PORT}" >"${STATE_FILE}"
    printf 'MYSQL_ROOT_PASSWORD=%s\n' "${ROOT_PASSWORD}" >"${STATE_DIR}/container.env"
    printf 'MYSQL_PWD=%s\n' "${ROOT_PASSWORD}" >"${STATE_DIR}/root-client.env"
    chmod 600 "${STATE_FILE}" "${STATE_DIR}/container.env" "${STATE_DIR}/root-client.env"

    UP_OK=0
    trap 'if [[ ${UP_OK} -eq 0 ]]; then log "up 失败，开始核实并清理本次容器"; \
        if remove_owned_container "${CONTAINER_ID}" "${RUN_ID}"; then remove_state_files; \
        else log "清理未完成，保留 ${STATE_FILE} 供 down 重试"; fi; fi' EXIT

    log "启动容器 ${CONTAINER_ID}（镜像 ${IMAGE}，仅发布 127.0.0.1:${HOST_PORT}）"
    container run -d \
        --name "${CONTAINER_ID}" \
        --label "${RUN_LABEL_KEY}=${RUN_ID}" \
        --label "${OWNER_LABEL}" \
        --env-file "${STATE_DIR}/container.env" \
        --publish "127.0.0.1:${HOST_PORT}:3306/tcp" \
        --tmpfs /var/lib/mysql \
        --cpus 2 \
        --memory 3G \
        "${IMAGE}" \
        mysqld \
        --character-set-server=utf8mb4 \
        --collation-server=utf8mb4_unicode_ci \
        --default-time-zone=+00:00 \
        --skip-log-bin >/dev/null

    local probe
    probe="$(probe_container "${CONTAINER_ID}" "${RUN_ID}")" || die "无法查询新容器状态"
    case "${probe}" in
        "OWNED "*" LOOPBACK") ;;
        *) die "新容器核实失败或端口未仅发布到 127.0.0.1（${probe}）" ;;
    esac

    log "等待 MySQL 就绪（上限 ${READY_TIMEOUT_SECONDS}s）"
    local deadline=$((SECONDS + READY_TIMEOUT_SECONDS))
    # 官方镜像初始化阶段的临时实例禁用 TCP，只有正式实例启动后 TCP 查询才会成功。
    until printf 'SELECT 1;\n' | mysql_root_exec -N >/dev/null 2>&1; do
        probe="$(probe_container "${CONTAINER_ID}" "${RUN_ID}")" || die "无法查询容器状态"
        if [[ "${probe}" != "OWNED running"* ]]; then
            print_log_tail "${CONTAINER_ID}"
            die "容器未处于运行状态（${probe}）"
        fi
        if (( SECONDS >= deadline )); then
            print_log_tail "${CONTAINER_ID}"
            die "MySQL 在 ${READY_TIMEOUT_SECONDS}s 内未就绪"
        fi
        sleep 2
    done

    log "按固定顺序执行 sql/001..011（001 自建库，其余显式 --database=${DB_NAME}）"
    local file
    for file in "${SQL_FILES[@]}"; do
        if [[ "${file}" == 001_* ]]; then
            mysql_root_exec <"${SQL_DIR}/${file}" || die "执行 ${file} 失败"
        else
            mysql_root_exec --database="${DB_NAME}" <"${SQL_DIR}/${file}" || die "执行 ${file} 失败"
        fi
        log "已执行 ${file}"
    done

    # 守护库：IT 启动时校验 run 标记，防止连到非本次专属库。应用账号仅有业务库 DML 与守护库只读权限。
    {
        printf "CREATE DATABASE %s DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;\n" "\`${GUARD_DB}\`"
        printf "CREATE TABLE %s.it_run_marker (run_id CHAR(32) NOT NULL PRIMARY KEY, created_at DATETIME(3) NOT NULL) ENGINE=InnoDB;\n" "\`${GUARD_DB}\`"
        printf "INSERT INTO %s.it_run_marker (run_id, created_at) VALUES ('%s', UTC_TIMESTAMP(3));\n" "\`${GUARD_DB}\`" "${RUN_ID}"
        printf "CREATE USER '%s'@'%%' IDENTIFIED BY '%s';\n" "${APP_USER}" "${APP_PASSWORD}"
        printf "GRANT SELECT, INSERT, UPDATE, DELETE ON %s.* TO '%s'@'%%';\n" "\`${DB_NAME}\`" "${APP_USER}"
        printf "GRANT SELECT ON %s.* TO '%s'@'%%';\n" "\`${GUARD_DB}\`" "${APP_USER}"
    } | mysql_root_exec || die "创建守护库或应用账号失败"

    # 主机侧确认回环端口可连通（有界等待）。
    python3 -I -c '
import socket, sys, time
port = int(sys.argv[1]); deadline = time.monotonic() + 60
while True:
    try:
        socket.create_connection(("127.0.0.1", port), timeout=2).close()
        break
    except OSError:
        if time.monotonic() > deadline:
            sys.exit("127.0.0.1 端口不可连通")
        time.sleep(1)
' "${HOST_PORT}" || die "主机侧 127.0.0.1:${HOST_PORT} 不可连通"

    printf "F1_IT_JDBC_URL='jdbc:mysql://127.0.0.1:%s/%s?connectionTimeZone=UTC&characterEncoding=UTF-8'\nF1_IT_DB_USERNAME='%s'\nF1_IT_DB_PASSWORD='%s'\nF1_IT_RUN_ID='%s'\n" \
        "${HOST_PORT}" "${DB_NAME}" "${APP_USER}" "${APP_PASSWORD}" "${RUN_ID}" >"${STATE_DIR}/connection.env"
    chmod 600 "${STATE_DIR}/connection.env"

    UP_OK=1
    trap - EXIT
    log "就绪：容器 ${CONTAINER_ID}，127.0.0.1:${HOST_PORT}，run 标记已写入 ${GUARD_DB}"
    log "连接环境文件（0600，含凭据，勿打印内容）：${STATE_DIR}/connection.env"
    printf '%s\n' "${STATE_DIR}/connection.env"
}

cmd_status() {
    require_tools
    resolve_state_dir
    if ! load_state; then
        log "无运行记录（${STATE_DIR}）"
        return 0
    fi
    local probe
    probe="$(probe_container "${CONTAINER_ID}" "${RUN_ID}")" || die "无法查询 container 列表"
    log "run=${RUN_ID:0:12}… 容器=${CONTAINER_ID} 端口=127.0.0.1:${HOST_PORT} 核实结果=${probe}"
    if [[ -f "${STATE_DIR}/connection.env" ]]; then
        log "连接环境文件：${STATE_DIR}/connection.env"
    fi
}

cmd_env() {
    resolve_state_dir
    [[ -f "${STATE_DIR}/connection.env" ]] || die "连接环境文件不存在，请先执行 up"
    printf '%s\n' "${STATE_DIR}/connection.env"
}

cmd_down() {
    require_tools
    resolve_state_dir
    if ! load_state; then
        log "无运行记录，未删除任何容器（${STATE_DIR}）"
        return 0
    fi
    if remove_owned_container "${CONTAINER_ID}" "${RUN_ID}"; then
        remove_state_files
        log "已删除本次容器 ${CONTAINER_ID} 并清除本地凭据文件"
    else
        die "未完成清理，保留 ${STATE_FILE}；请人工核实后重试 down"
    fi
}

case "${1:-}" in
    up) cmd_up ;;
    status) cmd_status ;;
    env) cmd_env ;;
    down) cmd_down ;;
    *)
        printf '用法: %s {up|status|env|down}\n' "$0" >&2
        exit 2
        ;;
esac
