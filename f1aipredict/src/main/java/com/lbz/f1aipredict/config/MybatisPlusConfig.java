package com.lbz.f1aipredict.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.BlockAttackInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis-Plus 配置类，统一注册 Mapper 扫描和数据库操作插件。
 * 扫描 question、sync、season 三个领域的 Mapper 包。
 * <p>
 * {@code lazyInitialization} 绑定已有 {@code spring.main.lazy-initialization}：
 * 生产缺省 false，Mapper 仍急切创建，行为不变；无 DataSource 的测试已设 true，
 * 避免 Boot 3 下 MapperFactoryBean 在缺 SqlSessionFactory 时立刻失败。
 */
@Configuration
@MapperScan(
        value = {
                "com.lbz.f1aipredict.question.mapper",
                "com.lbz.f1aipredict.sync.mapper",
                "com.lbz.f1aipredict.season.mapper"
        },
        lazyInitialization = "${spring.main.lazy-initialization:false}"
)
public class MybatisPlusConfig {

    /**
     * 注册 MySQL 分页和全表操作保护插件。
     *
     * @return MyBatis-Plus 插件拦截器
     */
    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        // 分页插件明确使用 MySQL 方言，确保分页 SQL 生成正确。
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));
        // 防止无条件更新或删除导致整张业务表被误操作。
        interceptor.addInnerInterceptor(new BlockAttackInnerInterceptor());
        return interceptor;
    }
}
