package com.cobot.dto;

import lombok.Data;

/**
 * 统一响应包装
 *
 * <p>所有 REST 接口返回 {code, msg, data} 结构，前端统一按 code==200 判定成功。
 */
@Data
public class Result<T> {

    /** 状态码：200 成功，500 失败 */
    private int code;

    /** 提示信息 */
    private String msg;

    /** 业务数据 */
    private T data;

    public static <T> Result<T> ok(T data) {
        Result<T> r = new Result<>();
        r.code = 200;
        r.msg = "success";
        r.data = data;
        return r;
    }

    public static <T> Result<T> ok() {
        return ok(null);
    }

    public static <T> Result<T> fail(String msg) {
        Result<T> r = new Result<>();
        r.code = 500;
        r.msg = msg;
        return r;
    }
}
