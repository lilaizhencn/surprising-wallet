package org.tron.wallet.exception;

/**
 * TRON钱包通用异常类，用于封装TRON相关操作中产生的各类异常。
 *
 * <p>继承自标准{@link Exception}，提供三种构造函数：</p>
 * <ul>
 *   <li>{@link #TronException()}：无参构造</li>
 *   <li>{@link #TronException(String)}：带消息的构造</li>
 *   <li>{@link #TronException(String, Throwable)}：带消息和原因的构造，支持异常链</li>
 * </ul>
 *
 * <p>用于TRON交易构建、签名、广播等各个环节的统一异常处理。</p>
 */
public class TronException extends Exception {

  public TronException() {
    super();
  }

  public TronException(String message) {
    super(message);
  }

  public TronException(String message, Throwable cause) {
    super(message, cause);
  }

}
