package com.opspilot.action;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import com.opspilot.auth.UserContext;
import com.opspilot.metrics.AuditService;
import com.opspilot.metrics.L1Metrics;
import com.opspilot.retrieval.InMemoryIndex;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 装配级回归锁（P0，2026-10-26）：{@link ActionOpsService} 在 **live 存储形态**下必须能装配。
 *
 * <p><b>为什么必须用 Spring 上下文测</b>：缺陷不在任何一段业务逻辑里，而在「构造器强依赖了一个
 * 只在 memory 模式注册的 bean」——单测里手工 new 一个 mock 索引永远看不到它。真实的失败形态是
 * 启动期 {@code NoSuchBeanDefinitionException}（{@code storage.mode=live} 下应用直接起不来），
 * 只有让 Spring 真的去解析依赖才会暴露。
 *
 * <p>锁两条：① live 下能装配、且上下文里确实没有 {@link InMemoryIndex}；② live 下提案被明确拒绝
 * 并说明原因（而不是静默变成「chunk 不存在」或让应用崩掉）。memory 形态的完整状态机由
 * {@link ActionOpsServiceTest} 覆盖，这里只钉「软依赖」这一条。
 */
class ActionOpsWiringTest {

    /** 只装被测服务与它的其它依赖（索引**故意不提供**——live 形态下真实容器里也没有它）。 */
    @Configuration
    @Import(ActionOpsService.class)
    static class Cfg {
        @Bean
        JdbcTemplate jdbcTemplate() {
            return mock(JdbcTemplate.class);
        }

        @Bean
        AuditService auditService() {
            return mock(AuditService.class);
        }

        @Bean
        L1Metrics l1Metrics() {
            return mock(L1Metrics.class);
        }

        @Bean
        ActionDispatchService dispatchService() {
            ActionDispatchService dispatch = mock(ActionDispatchService.class);
            when(dispatch.l1Enabled()).thenReturn(true);   // 让判定走到索引那一步
            return dispatch;
        }
    }

    private static ApplicationContextRunner runner() {
        return new ApplicationContextRunner().withUserConfiguration(Cfg.class);
    }

    @Test
    void liveStorageWiresServiceWithoutMemoryIndex() {
        runner().withPropertyValues("opspilot.storage.mode=live").run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx).hasSingleBean(ActionOpsService.class);
            assertThat(ctx).doesNotHaveBean(InMemoryIndex.class);
        });
    }

    @Test
    void memoryStorageStillWiresWithIndex() {
        InMemoryIndex index = mock(InMemoryIndex.class);
        runner()
                .withPropertyValues("opspilot.storage.mode=memory")
                .withBean(InMemoryIndex.class, () -> index)
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx).hasSingleBean(ActionOpsService.class);
                });
    }

    /**
     * live 下没有索引 ⇒ 4xx 业务拒绝 + 可执行的原因；**不许**落库、不许抛 NPE。
     * 若有人把这里改回「静默当作 chunk 不可见」，运维看到的将是一条 misleading 的 404 语义。
     */
    @Test
    void proposeUnderLiveStorageIsRejectedWithActionableReason() {
        ApplicationContextRunner runner = runner().withPropertyValues("opspilot.storage.mode=live");
        runner.run(ctx -> {
            assertThat(ctx).hasNotFailed();
            ActionOpsService ops = ctx.getBean(ActionOpsService.class);
            JdbcTemplate jdbc = ctx.getBean(JdbcTemplate.class);
            UserContext proposer = new UserContext("sre-full", "platform", 3, "tenant-internal");

            ActionOpsService.ActionRejectedException e = assertThrows(
                    ActionOpsService.ActionRejectedException.class,
                    () -> ops.propose(proposer, "rb-l1::恢复动作", "kubectl rollout restart deployment/order-api"));

            assertThat(e.stage).isEqualTo("l1_requires_memory_storage");
            assertThat(e.getMessage())
                    .contains("storage.mode=memory")
                    .contains("ES byId");
            verify(jdbc, never()).update(anyString(), org.mockito.ArgumentMatchers.<Object[]>any());
        });
    }
}
