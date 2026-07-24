package com.surprising.wallet.config.custody;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

public final class CachedBodyHttpServletRequest extends HttpServletRequestWrapper {
    /** 缓存后的请求体字节，用于签名过滤器重放读取。 */
    private final byte[] body;

    /**
     * 用已读取的原始字节构建可复用的请求包装对象。
     *
     * @param request 原始请求
     * @param body    已消费的 body 内容（已做拷贝）
     */
    public CachedBodyHttpServletRequest(HttpServletRequest request, byte[] body) {
        super(request);
        this.body = body.clone();
    }

    /**
     * 提供可重复读取的 InputStream。
     */
    @Override
    public ServletInputStream getInputStream() {
        ByteArrayInputStream input = new ByteArrayInputStream(body);
        return new ServletInputStream() {
            @Override
            public boolean isFinished() {
                return input.available() == 0;
            }

            @Override
            public boolean isReady() {
                return true;
            }

            @Override
            public void setReadListener(ReadListener readListener) {
                if (readListener == null) {
                    return;
                }
                try {
                    if (isFinished()) {
                        readListener.onAllDataRead();
                    } else {
                        readListener.onDataAvailable();
                    }
                } catch (IOException e) {
                    readListener.onError(e);
                }
            }

            @Override
            public int read() {
                return input.read();
            }
        };
    }

    /**
     * 提供字符流读取接口，供框架和 JSON 转换器复用。
     */
    @Override
    public BufferedReader getReader() {
        return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
    }
}
