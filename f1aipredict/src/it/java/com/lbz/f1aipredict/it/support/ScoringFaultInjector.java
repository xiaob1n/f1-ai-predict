package com.lbz.f1aipredict.it.support;

import com.lbz.f1aipredict.scoring.entity.BatchTotalScore;
import com.lbz.f1aipredict.scoring.mapper.BatchTotalScoreMapper;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongConsumer;

/**
 * 事务故障注入：包装真实 {@link BatchTotalScoreMapper} Bean，使指定批次的总分插入在执行前抛出异常。
 * <p>
 * 默认不生效；只有测试显式 {@link #armTotalInsertFailure} 后，匹配批次的 {@code insert} 才会失败。
 * 失败前先执行探针回调——此时仍处于评分事务内，探针可经同一事务连接读取已写入但未提交的明细，
 * 证明故障发生在“明细已插入之后”。其余方法与其他批次全部透传真实 Mapper。
 */
public class ScoringFaultInjector implements BeanPostProcessor {

    private final AtomicReference<Arm> armed = new AtomicReference<>();

    /**
     * 对指定批次布置一次总分插入失败。
     *
     * @param batchId     目标批次
     * @param insideProbe 抛出异常前在评分事务内执行的探针
     */
    public void armTotalInsertFailure(long batchId, LongConsumer insideProbe) {
        if (!armed.compareAndSet(null, new Arm(batchId, Objects.requireNonNull(insideProbe)))) {
            throw new IllegalStateException("已有未撤销的故障注入");
        }
    }

    /** 撤销故障注入，测试 finally 中必须调用。 */
    public void disarm() {
        armed.set(null);
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        if (!(bean instanceof BatchTotalScoreMapper target)) {
            return bean;
        }
        InvocationHandler handler = (proxy, method, args) -> {
            Arm arm = armed.get();
            if (arm != null && isTotalInsert(method, args)
                    && Objects.equals(((BatchTotalScore) args[0]).getBatchId(), arm.batchId())) {
                arm.insideProbe().accept(arm.batchId());
                throw new InjectedScoringFault("IT 注入：批次总分插入失败 batchId=" + arm.batchId());
            }
            try {
                return method.invoke(target, args);
            } catch (InvocationTargetException error) {
                throw error.getCause();
            }
        };
        return Proxy.newProxyInstance(BatchTotalScoreMapper.class.getClassLoader(),
                new Class<?>[]{BatchTotalScoreMapper.class}, handler);
    }

    private static boolean isTotalInsert(Method method, Object[] args) {
        return "insert".equals(method.getName()) && args != null && args.length == 1
                && args[0] instanceof BatchTotalScore;
    }

    private record Arm(long batchId, LongConsumer insideProbe) {
    }

    /** 注入的故障类型，测试据此确认失败来自注入点而非其他错误。 */
    public static class InjectedScoringFault extends RuntimeException {
        public InjectedScoringFault(String message) {
            super(message);
        }
    }
}
