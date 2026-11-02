package com.yh.ai.arg.knowledge.trigger.http;

import com.yh.ai.arg.knowledge.api.IRAGService;
import com.yh.ai.arg.knowledge.api.response.Response;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.api.CloneCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.redisson.api.RList;
import org.redisson.api.RedissonClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.ollama.OllamaChatClient;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.PgVectorStore;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.core.io.PathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.util.FileSystemUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * @Description
 * @Author fanyiheng
 * @Date 2026/11/2 14:58
 */
@Slf4j
@RestController()
@CrossOrigin("*")
@RequestMapping("/api/v1/rag/")
public class RAGController implements IRAGService {
    @Resource
    private OllamaChatClient ollamaChatClient;
    @Resource
    private TokenTextSplitter tokenTextSplitter;
    @Resource
    private SimpleVectorStore simpleVectorStore;
    @Resource
    private PgVectorStore pgVectorStore;
    @Resource
    private JdbcTemplate jdbcTemplate;
    @Resource
    private RedissonClient redissonClient;
    @RequestMapping(value = "query_rag_tag_list", method = RequestMethod.GET)
    @Override
    public Response<List<String>> queryRagTagList() {
        RList<String> elements = redissonClient.getList("ragTag");
        return Response.<List<String>>builder()
                .code("0000")
                .info("调用成功")
                .data(elements)
                .build();
    }

    @RequestMapping(value = "file/upload", method = RequestMethod.POST, headers = "content-type=multipart/form-data")
    @Override
    public Response<String> uploadFile(@RequestParam("ragTag") String ragTag, @RequestParam("file") List<MultipartFile> files) {
        log.info("上传知识库开始 {}", ragTag);

        for (MultipartFile file : files) {
            TikaDocumentReader reader = new TikaDocumentReader(file.getResource());
            List<Document> documents = reader.get();
            List<Document> documentSplitterList = tokenTextSplitter.apply(documents);

            documents.forEach(doc -> doc.getMetadata().put("knowledge", ragTag));
            documentSplitterList.forEach(doc -> doc.getMetadata().put("knowledge", ragTag));

            pgVectorStore.accept(documentSplitterList);
            RList<String> elements = redissonClient.getList("ragTag");
            if (!elements.contains(ragTag)){
                elements.add(ragTag);
            }
        }
        log.info("上传完成 {}", ragTag);
        return Response.<String>builder().code("0000").info("调用成功").build();
    }


    @RequestMapping(value = "analyze_git_repository", method = RequestMethod.POST)
    @Override
    public Response<String> analyzeGitRepository(@RequestParam("repoUrl") String repoUrl,
                                                 @RequestParam(value = "userName", required = false) String userName,
                                                 @RequestParam(value = "token", required = false) String token) {
        String repoProjectName = extractProjectName(repoUrl);
        Git git = null;
        Path localDir = null;
        try {
            // 1. 使用临时目录，避免固定路径在并发/重复调用时相互冲突
            localDir = Files.createTempDirectory("git-cloned-");
            log.info("开始克隆 {} -> {}", repoUrl, localDir.toAbsolutePath());

            // 2. 克隆仓库，60s 传输超时，避免网络不通时长时间挂起
            //    公开仓库无需凭据；私有仓库需要用户名 + 访问令牌（如 GitHub Personal Access Token）
            CloneCommand cloneCommand = Git.cloneRepository()
                    .setURI(repoUrl)
                    .setDirectory(localDir.toFile())
                    .setTimeout(60);
            if (StringUtils.hasText(userName) && StringUtils.hasText(token)) {
                cloneCommand.setCredentialsProvider(new UsernamePasswordCredentialsProvider(userName, token));
            }
            git = cloneCommand.call();
            log.info("克隆完成：{}", repoProjectName);

            // 3. 重新解析前先清理该知识库标签下的旧数据（幂等：重复导入同一仓库不会产生重复向量）
            int deleted = jdbcTemplate.update(
                    "DELETE FROM vector_store WHERE metadata->>'knowledge' = ?", repoProjectName);
            log.info("已清理知识库[{}]旧数据 {} 行", repoProjectName, deleted);

            // 3. 允许解析的文件后缀白名单
            List<String> allowedExtensions = Arrays.asList(".java", ".md", ".txt", ".sql", ".xml", ".yml", ".yaml", ".properties");
            // 3.1 跳过这些目录（IDE 配置、构建产物、依赖等，没有知识价值）
            List<String> skipDirectories = Arrays.asList(".git", ".idea", "target", "build", "node_modules");

            // 4. 遍历仓库文件，解析为文档块，先积攒再批量入库（避免频繁建连）
            List<Document> allDocumentsToUpload = new ArrayList<>();
            Files.walkFileTree(localDir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    String dirName = dir.getFileName().toString();
                    if (skipDirectories.contains(dirName)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    String fileName = file.getFileName().toString().toLowerCase();
                    // 跳过空文件（如 package-info.java）和不在白名单内的文件
                    if (attrs.size() == 0
                            || allowedExtensions.stream().noneMatch(fileName::endsWith)) {
                        return FileVisitResult.CONTINUE;
                    }
                    try {
                        TikaDocumentReader reader = new TikaDocumentReader(new PathResource(file));
                        List<Document> documents = reader.get();
                        if (documents.isEmpty()) {
                            return FileVisitResult.CONTINUE;
                        }
                        List<Document> documentSplitterList = tokenTextSplitter.apply(documents);
                        documents.forEach(doc -> doc.getMetadata().put("knowledge", repoProjectName));
                        documentSplitterList.forEach(doc -> doc.getMetadata().put("knowledge", repoProjectName));
                        allDocumentsToUpload.addAll(documentSplitterList);
                        log.info("已解析文件：{}", file.getFileName());
                    } catch (Exception e) {
                        log.warn("解析文件失败，跳过（不影响整体）：{} - {}", file, e.getMessage());
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    log.info("无法访问文件：{} - {}", file, exc.getMessage());
                    return FileVisitResult.CONTINUE;
                }
            });

            // 5. 批量写入向量库。
            //    注意：PgVectorStore 会在一次 accept 内先对所有文档做 embedding（远程 Ollama 较慢），再一次性 INSERT。
            //    批次过大时单次耗时过长，数据库连接容易被服务端/网络掐断（08006/Broken pipe）。
            //    因此用小批次（20 块）+ 单批自动重试，降低对单次连接稳定性的依赖。
            if (!allDocumentsToUpload.isEmpty()) {
                log.info("{} 共解析 {} 个文档块，开始批量写入向量库", repoProjectName, allDocumentsToUpload.size());
                int batchSize = 20;
                for (int i = 0; i < allDocumentsToUpload.size(); i += batchSize) {
                    int end = Math.min(i + batchSize, allDocumentsToUpload.size());
                    writeBatchWithRetry(allDocumentsToUpload.subList(i, end), repoProjectName, 3);
                    log.info("已写入 {}/{}", end, allDocumentsToUpload.size());
                }
            } else {
                log.warn("{} 未找到符合条件的可解析文件", repoProjectName);
            }

            // 6. 登记 ragTag，供前端下拉框选择
            RList<String> elements = redissonClient.getList("ragTag");
            if (!elements.contains(repoProjectName)) {
                elements.add(repoProjectName);
            }

            log.info("Git 仓库解析完成：{}", repoUrl);
            return Response.<String>builder().code("0000").info("调用成功").data(repoProjectName).build();
        } catch (Exception e) {
            log.error("Git 仓库解析失败：{}", repoUrl, e);
            return Response.<String>builder().code("9999").info("解析失败：" + e.getMessage()).build();
        } finally {
            // 7. 无论成败，释放资源并清理临时目录
            if (git != null) {
                git.close();
            }
            if (localDir != null) {
                FileSystemUtils.deleteRecursively(localDir.toFile());
            }
        }
    }

    private String extractProjectName(String repoUrl) {
        String url = repoUrl.trim();
        if (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        String[] parts = url.split("/");
        String projectNameWithGit = parts[parts.length - 1];
        return projectNameWithGit.replace(".git", "");
    }

    /**
     * 写入一批文档；若数据库连接被掐断则重试（Hikari 会把坏连接剔除，下次借出的是新连接）
     */
    private void writeBatchWithRetry(List<Document> batch, String ragTag, int maxAttempts) {
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                pgVectorStore.accept(batch);
                return;
            } catch (Exception e) {
                if (attempt == maxAttempts) {
                    throw e;
                }
                log.warn("写入批次失败（第 {}/{} 次），稍后重试：{}", attempt, maxAttempts, e.getMessage());
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(ie);
                }
            }
        }
    }
}
