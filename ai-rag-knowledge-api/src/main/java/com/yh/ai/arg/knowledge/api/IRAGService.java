package com.yh.ai.arg.knowledge.api;

import com.yh.ai.arg.knowledge.api.response.Response;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * @Description
 * @Author fanyiheng
 * @Date 2026/11/2 14:59
 */
public interface IRAGService {
    Response<List<String>> queryRagTagList();

    Response<String> uploadFile(String ragTag, List<MultipartFile> files);
}
