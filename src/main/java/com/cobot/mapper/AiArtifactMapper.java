package com.cobot.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cobot.entity.AiArtifact;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.type.ByteArrayTypeHandler;

/**
 * AI 生成产物 Mapper（对应表 ai_artifact）
 */
@Mapper
public interface AiArtifactMapper extends BaseMapper<AiArtifact> {

    /**
     * 按 ID 取产物（含 .pptx 文件二进制）
     *
     * <p>实体上 fileData 标了 {@code @TableField(select = false)}，常规查询拿不到它；
     * 只有下载接口需要大字段，这里用独立 SQL 精准取一行。
     *
     * <p><b>为什么必须手写 typeHandler：</b>
     * 若直接把方法返回类型写成 {@code byte[]}，MyBatis 会依据 JDBC 元数据自行推断处理器，
     * 在 MySQL + LONGBLOB 组合下会推断成按单字节（java.lang.Byte）读取，
     * 从而抛出 {@code SQLDataException: ... is outside of valid range for type java.lang.Byte}。
     * 这里显式绑定 {@link ByteArrayTypeHandler}（底层调用 {@code ResultSet.getBytes()}），
     * 稳定地把 LONGBLOB 读成字节数组。
     */
    @Select("SELECT id, file_data, file_path FROM ai_artifact WHERE id = #{id}")
    @Results({
            @Result(column = "id", property = "id", id = true),
            @Result(column = "file_data", property = "fileData", typeHandler = ByteArrayTypeHandler.class),
            @Result(column = "file_path", property = "filePath")
    })
    AiArtifact selectWithFile(@Param("id") Long id);
}
