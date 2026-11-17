package com.opspilot.config;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import com.opspilot.domain.DomainPack;
import com.opspilot.llm.MockEngine;

/**
 * Mock 模式下启动时从 chunks.jsonl 构建 IDF 表，
 * 保证在线查询向量与存量向量使用同一套词法权重。
 */
@Component
public class MockIdfInitializer {

    private static final Logger log = LoggerFactory.getLogger(MockIdfInitializer.class);

    private final OpsPilotProperties props;
    private final String chunksPath;

    /**
     * 生产构造器（ADR-0031 D3）：语料默认路径来自注入的领域包；
     * {@code opspilot.chunks-path} 仍是部署者可信的运维覆盖口（保留）。
     */
    @Autowired
    public MockIdfInitializer(OpsPilotProperties props, DomainPack domain,
                              @Value("${opspilot.chunks-path:}") String chunksPath) {
        this.props = props;
        this.chunksPath = chunksPath.isBlank() ? domain.corpusPath() : chunksPath;
    }

    /** test-only / 兼容构造器：委托到 {@link DomainPack#sre()}，生产 bean 走上面的注入构造器。 */
    public MockIdfInitializer(OpsPilotProperties props, String chunksPath) {
        this(props, DomainPack.sre(), chunksPath);
    }

    @PostConstruct
    public void init() {
        if (props.dashscope().live()) return;
        Path p = Path.of(chunksPath);
        if (!Files.exists(p)) {
            log.warn("mock IDF 初始化跳过：未找到 {}", chunksPath);
            return;
        }
        try {
            ObjectMapper mapper = new ObjectMapper();
            List<String> texts = new ArrayList<>();
            for (String line : Files.readAllLines(p)) {
                if (line.isBlank()) continue;
                JsonNode n = mapper.readTree(line);
                texts.add(n.path("text").asText(""));
            }
            MockEngine.initIdf(texts);
            log.info("mock IDF 表已构建：{} 篇文档", texts.size());
        } catch (Exception e) {
            log.warn("mock IDF 初始化失败: {}", e.getMessage());
        }
    }
}
