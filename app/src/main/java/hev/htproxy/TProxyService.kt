package hev.htproxy

/**
 * libhev-socks5-tunnel.so 의 JNI_OnLoad 가 참조하는 클래스.
 * 실제 터널 제어는 com.mdkdw1.myinternet.HevTunnelManager 에서 수행.
 * 이 클래스는 JNI 함수 등록을 위한 "이름 제공자" 역할만 함.
 */
object TProxyService {
    @JvmStatic external fun TProxyStartService(configPath: String, fd: Int): Boolean
    @JvmStatic external fun TProxyStopService()
    @JvmStatic external fun TProxyGetStats(): LongArray?
}
