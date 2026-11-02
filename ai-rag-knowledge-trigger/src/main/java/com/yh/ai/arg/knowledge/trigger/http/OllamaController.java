package com.yh.ai.arg.knowledge.trigger.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yh.ai.arg.knowledge.api.IAiService;
import com.yh.ai.arg.knowledge.api.response.Response;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.ChatResponse;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.prompt.SystemPromptTemplate;
import org.springframework.ai.document.Document;
import org.springframework.ai.ollama.OllamaChatClient;
import org.springframework.ai.ollama.api.OllamaOptions;
import org.springframework.ai.vectorstore.PgVectorStore;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * @Description
 * @Author fanyiheng
 * @Date 2026/10/31 23:48
 */

@Slf4j
@RestController()
@CrossOrigin("*")
@RequestMapping("/api/v1/ollama/")
public class OllamaController implements IAiService {

    private static final String RAG_SYSTEM_PROMPT = """
            Use the information from the DOCUMENTS section to provide accurate answers but act as if you knew this information innately.
            If unsure, simply state that you don't know.
            Another thing you need to note is that your reply must be in Chinese!
            DOCUMENTS:
                {documents}
            """;

    private static final int TOP_K = 5;

    @Resource
    private OllamaChatClient chatClient;

    @Resource
    private PgVectorStore pgVectorStore;

    @Value("${spring.ai.ollama.base-url}")
    private String ollamaBaseUrl;

    /**
     * 列出 Ollama 上可用的问答（聊天）模型，供前端模型下拉框使用
     */
    @RequestMapping(value = "models", method = RequestMethod.GET)
    public Response<List<String>> models() {
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(5))
                    .build();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(ollamaBaseUrl + "/api/tags"))
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                return Response.<List<String>>builder().code("9999")
                        .info("获取模型列表失败: HTTP " + response.statusCode()).build();
            }

            JsonNode root = new ObjectMapper().readTree(response.body());
            List<String> chatModels = new ArrayList<>();
            for (JsonNode node : root.path("models")) {
                String name = node.path("name").asText();
                if (name.isBlank()) {
                    continue;
                }
                // 只保留可做问答的模型：有能力信息时看 capabilities，没有时按名称排除纯嵌入模型（nomic-embed-text 等）
                JsonNode caps = node.path("capabilities");
                boolean isChat;
                if (caps.isArray() && caps.size() > 0) {
                    boolean hasCompletion = false;
                    boolean hasEmbed = false;
                    for (JsonNode c : caps) {
                        String s = c.asText();
                        if ("completion".equals(s) || "chat".equals(s)) {
                            hasCompletion = true;
                        }
                        if ("embed".equals(s) || "embedding".equals(s)) {
                            hasEmbed = true;
                        }
                    }
                    isChat = hasCompletion || !hasEmbed;
                } else {
                    isChat = !name.toLowerCase().contains("embed");
                }
                if (isChat) {
                    chatModels.add(name);
                }
            }
            return Response.<List<String>>builder().code("0000").info("调用成功").data(chatModels).build();
        } catch (Exception e) {
            log.error("获取模型列表失败", e);
            return Response.<List<String>>builder().code("9999").info("获取模型列表失败: " + e.getMessage()).build();
        }
    }

    @RequestMapping(value = "generate", method = RequestMethod.GET)
    @Override
    public ChatResponse generate(@RequestParam("model") String model, @RequestParam("message") String message) {
        return chatClient.call(new Prompt(message, OllamaOptions.create().withModel(model)));
    }

    @RequestMapping(value = "generate_stream", method = RequestMethod.GET)
    @Override
    public Flux<ChatResponse> generateStream(@RequestParam("model") String model, @RequestParam("message") String message) {
        return chatClient.stream(new Prompt(message, OllamaOptions.create().withModel(model)));
    }

    @RequestMapping(value = "generate_with_rag", method = RequestMethod.GET)
    @Override
    public ChatResponse generateWithRag(@RequestParam("model") String model, @RequestParam("message") String message,
                                        @RequestParam(value = "ragTag", required = false) String ragTag) {
        if (StringUtils.hasText(ragTag)) {
            return chatClient.call(buildRagPrompt(model, message, ragTag));
        }
        return generate(model, message);
    }

    @RequestMapping(value = "generate_stream_with_rag", method = RequestMethod.GET)
    @Override
    public Flux<ChatResponse> generateWithRagStream(@RequestParam("model") String model, @RequestParam("message") String message,
                                                    @RequestParam(value = "ragTag", required = false) String ragTag) {
        if (StringUtils.hasText(ragTag)) {
            return chatClient.stream(buildRagPrompt(model, message, ragTag));
        }
        return generateStream(model, message);
    }

    private Prompt buildRagPrompt(String model, String message, String ragTag) {
        // 1. 按知识库标签检索向量库（与上传时写入的 knowledge 元数据标签对应）
        SearchRequest request = SearchRequest.query(message)
                .withTopK(TOP_K)
                .withFilterExpression("knowledge == '" + ragTag.replace("'", "''") + "'");
        List<Document> documents = pgVectorStore.similaritySearch(request);
        log.info("RAG 检索知识库[{}] 命中 {} 个文档块", ragTag, documents.size());

        // 2. 拼接检索到的文档块，作为模型上下文
        String documentsCollectors = documents.stream()
                .map(Document::getContent)
                .collect(Collectors.joining("\n\n---\n\n"));

        // 3. 组装 Prompt（System 携带文档上下文，User 为用户问题）
        Message ragMessage = new SystemPromptTemplate(RAG_SYSTEM_PROMPT)
                .createMessage(Map.of("documents", documentsCollectors));

        ArrayList<Message> messages = new ArrayList<>();
        messages.add(ragMessage);
        messages.add(new UserMessage(message));

        return new Prompt(messages, OllamaOptions.create().withModel(model));
    }
}
