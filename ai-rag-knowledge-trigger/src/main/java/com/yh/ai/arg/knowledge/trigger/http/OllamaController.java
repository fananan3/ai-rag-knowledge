package com.yh.ai.arg.knowledge.trigger.http;

import com.yh.ai.arg.knowledge.api.IAiService;
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
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

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
