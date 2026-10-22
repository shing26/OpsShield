package com.opspilot.ingest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import com.opspilot.config.OpsPilotProperties;

/**
 * 启动期 SOP 清单补灌（G1-W1.3，2026-10-22 运维审查）。
 *
 * <p><b>为什么需要它</b>：SOP 键现在带 7 天 TTL（{@code IngestionRunner.SOP_TTL}），这是让
 * "语料删了、旧止损步骤还挂着"不再发生的代价——代价是**长时间不 reingest 的部署，清单会自己过期**，
 * 而 L2 档位（熔断直出）没有第二份数据来源：清单空了，用户只会收到一句"暂无可用止损清单"。
 * 本 runner 在每次启动时检查"哪些租户的 {@code sop:<tenant>:steps} 缺失"，只补缺的那些。
 *
 * <p><b>为什么 order 排在最后</b>：① 让 {@link IngestionRunner}（{@code LOWEST_PRECEDENCE - 100}）
 * 先完成首灌/内存装载——它本身会 warm，补灌就成了空操作；② ApplicationRunner 都在端口开放之后运行，
 * 补灌慢不挡对外服务；③ 补灌是修复而非主链路，晚到不影响正确性。
 *
 * <p><b>失败绝不阻断启动</b>：本类再包一层 catch。{@code ensureSopWarm()} 内部已吞异常，
 * 但 ApplicationRunner 抛异常会让 Spring 判定启动失败——"SOP 清单没补上"与"网关起不来"
 * 不是同一等级的故障，不能让前者升级成后者。
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
public class SopWarmupRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SopWarmupRunner.class);

    private final IngestionRunner ingestion;
    private final OpsPilotProperties props;

    public SopWarmupRunner(IngestionRunner ingestion, OpsPilotProperties props) {
        this.ingestion = ingestion;
        this.props = props;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (props.storage().inMemory()) {
            // memory 模式在 IngestionRunner.afterPropertiesSet 里已全量装载并 warm，
            // 之后再查一次只会多一次 chunks 文件读取，没有收益。
            return;
        }
        try {
            ingestion.ensureSopWarm();
        } catch (Exception e) {
            // 双保险：ApplicationRunner 抛异常会让启动失败，而补灌失败不该有这种权力
            log.warn("SOP 启动补灌异常（已忽略，不影响启动）: {}", e.toString());
        }
    }
}
