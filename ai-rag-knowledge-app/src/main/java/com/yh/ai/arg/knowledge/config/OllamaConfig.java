package com.yh.ai.arg.knowledge.config;

import org.springframework.ai.embedding.EmbeddingClient;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.ollama.OllamaChatClient;
import org.springframework.ai.ollama.OllamaEmbeddingClient;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaOptions;
import org.springframework.ai.openai.OpenAiEmbeddingClient;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.PgVectorStore;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.util.StringUtils;

/**
 * @Description
 * @Author fanyiheng
 * @Date 2026/10/31 23:48
 */

@Configuration
public class OllamaConfig {

    @Bean
    public OllamaApi ollamaApi(@Value("${spring.ai.ollama.base-url}") String baseUrl) {
        return new OllamaApi(baseUrl);
    }

    @Bean
    public OllamaChatClient ollamaChatClient(OllamaApi ollamaApi) {
        return new OllamaChatClient(ollamaApi);
    }

    @Bean
    public TokenTextSplitter tokenTextSplitter() {
        return new TokenTextSplitter();
    }

    /**
     * 嵌入模型按配置选择：
     * ai.embedding.backend=ollama（默认，本地模型）或 openai（云端，用 ai.embedding.external.* 独立配置，可与问答不同服务商）
     */
    private EmbeddingClient buildEmbeddingClient(OllamaApi ollamaApi, String backend,
                                                 String ollamaModel,
                                                 String extBaseUrl, String extApiKey, String extModel) {
        if ("openai".equalsIgnoreCase(backend) && StringUtils.hasText(extApiKey)) {
            OpenAiApi api = new OpenAiApi(extBaseUrl, extApiKey);
            String model = StringUtils.hasText(extModel) ? extModel : "text-embedding-3-small";
            return new OpenAiEmbeddingClient(api, MetadataMode.EMBED,
                    OpenAiEmbeddingOptions.builder().withModel(model).build(),
                    RetryTemplate.defaultInstance());
        }
        OllamaEmbeddingClient client = new OllamaEmbeddingClient(ollamaApi);
        String model = StringUtils.hasText(ollamaModel) ? ollamaModel : "nomic-embed-text";
        client.withDefaultOptions(OllamaOptions.create().withModel(model));
        return client;
    }

    @Bean
    public SimpleVectorStore simpleVectorStore(OllamaApi ollamaApi,
                                               @Value("${ai.embedding.backend:ollama}") String backend,
                                               @Value("${ai.embedding.model:}") String ollamaModel,
                                               @Value("${ai.embedding.external.base-url:}") String extBaseUrl,
                                               @Value("${ai.embedding.external.api-key:}") String extApiKey,
                                               @Value("${ai.embedding.external.model:}") String extModel) {
        return new SimpleVectorStore(buildEmbeddingClient(ollamaApi, backend, ollamaModel, extBaseUrl, extApiKey, extModel));
    }

    @Bean
    public PgVectorStore pgVectorStore(OllamaApi ollamaApi, JdbcTemplate jdbcTemplate,
                                       @Value("${ai.embedding.backend:ollama}") String backend,
                                       @Value("${ai.embedding.model:}") String ollamaModel,
                                       @Value("${ai.embedding.external.base-url:}") String extBaseUrl,
                                       @Value("${ai.embedding.external.api-key:}") String extApiKey,
                                       @Value("${ai.embedding.external.model:}") String extModel) {
        return new PgVectorStore(jdbcTemplate, buildEmbeddingClient(ollamaApi, backend, ollamaModel, extBaseUrl, extApiKey, extModel));
    }

}
