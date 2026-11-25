package com.cryptobench.benchmark.execution.service;

import com.cryptobench.benchmark.execution.model.TaskDefinition;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

/**
 * Materializes public task inputs into the Agent request.
 *
 * The public catalog keeps file URLs for browser downloads, while execution
 * needs actual bytes because Runner and Docker Agents do not share the website
 * filesystem. Base64 keeps JSON transport lossless for text and binary inputs.
 */
@Service
public class TaskInputService {
    private static final int MAX_FILE_BYTES = 8_000_000;

    public void attach(String datasetId, String version, TaskDefinition task, ObjectNode publicTask) {
        ArrayNode files = publicTask.putArray("files");
        for (String declared : task.publicFiles()) {
            String relative = safeRelative(declared);
            String resourcePath = "/static/data/" + datasetId + "/" + version + "/" + relative;
            byte[] content = read(resourcePath, task.id());
            if (content.length > MAX_FILE_BYTES) {
                throw new IllegalStateException("Task input is too large: " + task.id() + " / " + relative);
            }
            ObjectNode file = files.addObject();
            file.put("path", workspacePath(task.id(), relative));
            file.put("url", "data/" + datasetId + "/" + version + "/" + relative);
            file.put("bytes", content.length);
            file.put("sha256", sha256(content));
            file.put("mediaType", mediaType(relative));
            file.put("encoding", "base64");
            file.put("content", Base64.getEncoder().encodeToString(content));
        }
    }

    private byte[] read(String resourcePath, String taskId) {
        ClassPathResource resource = new ClassPathResource(resourcePath);
        if (!resource.exists()) {
            throw new IllegalStateException("Task input is missing: " + taskId + " / " + resourcePath);
        }
        try (InputStream input = resource.getInputStream()) {
            return input.readAllBytes();
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot read task input: " + taskId + " / " + resourcePath, exception);
        }
    }

    private static String safeRelative(String value) {
        if (value == null || value.isBlank() || value.startsWith("/") || value.startsWith("\\")) {
            throw new IllegalArgumentException("Task input path is invalid");
        }
        String normalized = value.replace('\\', '/');
        if (Path.of(normalized).isAbsolute()
                || java.util.Arrays.stream(normalized.split("/")).anyMatch(".."::equals)) {
            throw new IllegalArgumentException("Task input path escapes the public task directory");
        }
        return normalized;
    }

    private static String workspacePath(String taskId, String relative) {
        String publicPrefix = "tasks/" + taskId + "/public/";
        if (relative.startsWith(publicPrefix)) return relative.substring(publicPrefix.length());
        int publicMarker = relative.indexOf("/public/");
        if (publicMarker >= 0) return relative.substring(publicMarker + "/public/".length());
        int slash = relative.lastIndexOf('/');
        return slash >= 0 ? relative.substring(slash + 1) : relative;
    }

    private static String mediaType(String relative) {
        String lower = relative.toLowerCase(java.util.Locale.ROOT);
        if (lower.endsWith(".json")) return "application/json";
        if (lower.endsWith(".md")) return "text/markdown";
        if (lower.endsWith(".csv")) return "text/csv";
        if (lower.endsWith(".txt") || lower.endsWith(".log")) return "text/plain";
        if (lower.endsWith(".pdf")) return "application/pdf";
        return "application/octet-stream";
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
