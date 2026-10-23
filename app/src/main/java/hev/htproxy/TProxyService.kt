package hev.htproxy

/**
 * libhev-socks5-tunnel.so 의 JNI_OnLoad 가 참조하는 클래스.
 * C 라이브러리가 기대하는 시그니처와 정확히 일치해야 함:
 *   void TProxyStartService(String, int)
 *   void TProxyStopService()
 *   long[] TProxyGetStats()
 */
object TProxyService {
    @JvmStatic external fun TProxyStartService(configPath: String, fd: Int)
    @JvmStatic external fun TProxyStopService()
    @JvmStatic external fun TProxyGetStats(): LongArray?
}
