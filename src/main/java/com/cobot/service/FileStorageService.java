package com.cobot.service;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;

/**
 * 文件存储服务（附件与 AI 产物统一走磁盘）
 *
 * <p>为什么把文件从数据库搬出来：
 * <ul>
 *   <li>ai_artifact.file_data 是 LONGBLOB，PPT 一多会把该表撑成"大对象仓库"，
 *       拖慢列表查询与备份（历史上还踩过驱动把 LONGBLOB 当 Byte 读的坑）</li>
 *   <li>磁盘/对象存储更适合放二进制，库里只留一个路径，读写都清爽</li>
 * </ul>
 *
 * <p>目录结构（相对 {@code cobot.storage.root}，默认 ./data）：
 * <pre>
 *   data/artifacts/{teamId}/{uuid}_{文件名}      AI 工作台产物（.pptx 等）
 *   data/attachments/{teamId}/{uuid}_{文件名}    聊天室上传的图片/文件
 * </pre>
 *
 * <p>安全：文件名一律经过清洗，去掉路径分隔符与上跳片段，防止目录穿越。
 */
@Slf4j
@Service
public class FileStorageService {

    /** 产物子目录 */
    private static final String ARTIFACT_DIR = "artifacts";

    /** 聊天附件子目录 */
    private static final String ATTACHMENT_DIR = "attachments";

    @Value("${cobot.storage.root:./data}")
    private String storageRoot;

    private Path rootPath;

    /** 启动时确保根目录存在 */
    @PostConstruct
    public void init() {
        rootPath = Paths.get(storageRoot).toAbsolutePath().normalize();
        try {
            Files.createDirectories(rootPath);
            log.info("[CoBot] 文件存储根目录：{}", rootPath);
        } catch (IOException e) {
            log.error("[CoBot] 创建文件存储目录失败：{}", rootPath, e);
        }
    }

    /**
     * 保存 AI 产物文件
     *
     * @param teamId   团队 ID（按团队分目录，便于清理与隔离）
     * @param fileName 原始文件名（仅用于展示与后缀识别）
     * @param bytes    文件字节
     * @return 相对存储路径（落库用）；失败返回 null
     */
    public String saveArtifact(Long teamId, String fileName, byte[] bytes) {
        return save(ARTIFACT_DIR, teamId, fileName, bytes);
    }

    /**
     * 保存聊天室附件（图片 / 文件）
     *
     * @return 相对存储路径；失败返回 null
     */
    public String saveAttachment(Long teamId, MultipartFile file) {
        try {
            return save(ATTACHMENT_DIR, teamId, file.getOriginalFilename(), file.getBytes());
        } catch (IOException e) {
            log.error("[CoBot] 读取上传文件失败", e);
            return null;
        }
    }

    /**
     * 读取文件字节
     *
     * @param relativePath 相对路径（落库的那个值）
     * @return 文件内容；不存在或读取失败返回 null
     */
    public byte[] read(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            return null;
        }
        try {
            Path target = resolveSafely(relativePath);
            if (target == null || !Files.exists(target)) {
                return null;
            }
            return Files.readAllBytes(target);
        } catch (IOException e) {
            log.error("[CoBot] 读取文件失败：{}", relativePath, e);
            return null;
        }
    }

    /**
     * 删除文件（产物被清理时调用）
     */
    public void delete(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            return;
        }
        try {
            Path target = resolveSafely(relativePath);
            if (target != null) {
                Files.deleteIfExists(target);
            }
        } catch (IOException e) {
            log.warn("[CoBot] 删除文件失败：{}", relativePath, e);
        }
    }

    /** 落盘核心逻辑 */
    private String save(String category, Long teamId, String fileName, byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return null;
        }
        try {
            String safeName = sanitize(fileName);
            Path dir = rootPath.resolve(category).resolve(String.valueOf(teamId == null ? 0L : teamId));
            Files.createDirectories(dir);
            // uuid 前缀避免同名覆盖
            String stored = UUID.randomUUID().toString().replace("-", "") + "_" + safeName;
            Path target = dir.resolve(stored);
            Files.write(target, bytes);
            // 统一用正斜杠存库，跨平台可读
            return rootPath.relativize(target).toString().replace('\\', '/');
        } catch (IOException e) {
            log.error("[CoBot] 写入文件失败：category={} name={}", category, fileName, e);
            return null;
        }
    }

    /** 把相对路径解析为绝对路径，并确保没有越出根目录 */
    private Path resolveSafely(String relativePath) {
        Path target = rootPath.resolve(relativePath).normalize();
        if (!target.startsWith(rootPath)) {
            log.warn("[CoBot] 非法文件路径（疑似目录穿越）：{}", relativePath);
            return null;
        }
        return target;
    }

    /** 清洗文件名：只保留基础名，去掉路径分隔符与危险字符 */
    private String sanitize(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return "unnamed";
        }
        String name = fileName.replace('\\', '/');
        int slash = name.lastIndexOf('/');
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        name = name.replaceAll("[\\p{Cntrl}<>:\"|?*]", "_").trim();
        if (name.isEmpty() || ".".equals(name) || "..".equals(name)) {
            return "unnamed";
        }
        return name.length() > 120 ? name.substring(name.length() - 120) : name;
    }
}
