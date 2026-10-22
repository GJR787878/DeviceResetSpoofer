package io.github.gjr787878.devicereset;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.util.Base64;

/**
 * 极简 ADB 协议客户端（本地连接 adbd，用于经 adbd 执行命令——
 * 使 lspctl 的进程祖先链含 adbd，通过官方 CliOriginVerifier）。
 *
 * <p>协议：CNXN 握手 → AUTH（RSA 签名 token；公钥已由 root 写入
 * /data/misc/adb/adb_keys，免弹窗授权）→ OPEN shell 流 → WRTE/OKAY 交互。</p>
 */
public class AdbClient {

    // ADB 协议命令
    private static final int A_CNXN = 0x4e584e43;
    private static final int A_AUTH = 0x48545541;
    private static final int A_OPEN = 0x4e45504f;
    private static final int A_WRTE = 0x45545257;
    private static final int A_CLSE = 0x45534c43;
    private static final int A_OKAY = 0x59414b4f;

    private static final int AUTH_TOKEN = 1;
    private static final int AUTH_SIGNATURE = 2;
    private static final int AUTH_RSA_PUBLIC_KEY = 3;

    private static final int A_VERSION = 0x01000001;
    private static final int MAX_PAYLOAD = 256 * 1024;
    private static final int HEADER_LEN = 24;

    private final String host;
    private final int port;
    private final KeyPair keyPair;

    private Socket socket;
    private DataInputStream in;
    private DataOutputStream out;
    private int localId = 1;

    public AdbClient(String host, int port, KeyPair keyPair) {
        this.host = host;
        this.port = port;
        this.keyPair = keyPair;
    }

    /** 连接并完成握手/认证。失败快速返回（AUTH 全程 ≤ ~8s），避免 UI 长时间转圈。 */
    public void connect() throws Exception {
        socket = new Socket();
        socket.setTcpNoDelay(true);
        socket.connect(new InetSocketAddress(host, port), 3000);
        in = new DataInputStream(socket.getInputStream());
        socket.setSoTimeout(5000);
        out = new DataOutputStream(socket.getOutputStream());

        // 发送 CNXN
        send(A_CNXN, A_VERSION, MAX_PAYLOAD, "host::\0".getBytes(StandardCharsets.UTF_8));

        // 等待 CNXN 或 AUTH
        while (true) {
            Message msg = readMessage();
            if (msg.command == A_CNXN) {
                return; // 已认证（key 已在 adb_keys）
            } else if (msg.command == A_AUTH) {
                if (msg.arg0 == AUTH_TOKEN) {
                    // 用私钥对 token 做 SHA1withRSA 签名
                    Signature sig = Signature.getInstance("SHA1withRSA");
                    sig.initSign(keyPair.getPrivate());
                    sig.update(msg.payload);
                    byte[] signed = sig.sign();
                    send(A_AUTH, AUTH_SIGNATURE, 0, signed);
                } else {
                    // 发送公钥
                    String pub = encodePublicKey(keyPair) + "\0";
                    send(A_AUTH, AUTH_RSA_PUBLIC_KEY, 0, pub.getBytes(StandardCharsets.UTF_8));
                }
            } else {
                throw new IOException("unexpected cmd during connect: 0x"
                        + Integer.toHexString(msg.command));
            }
        }
    }

    /** 打开 shell 执行命令，返回输出。 */
    public String shell(String command) throws Exception {
        int id = localId++;
        String dest = "shell:" + command + "\0";
        send(A_OPEN, id, 0, dest.getBytes(StandardCharsets.UTF_8));

        StringBuilder result = new StringBuilder();
        int remoteId = 0;
        socket.setSoTimeout(15000);
        while (true) {
            Message msg;
            try {
                msg = readMessage();
            } catch (IOException eof) {
                break;
            }
            if (msg.arg1 != id && msg.command != A_CLSE) continue;
            switch (msg.command) {
                case A_OKAY:
                    remoteId = msg.arg0;
                    break;
                case A_WRTE:
                    result.append(new String(msg.payload, StandardCharsets.UTF_8));
                    // 回复 OKAY 确认
                    send(A_OKAY, id, msg.arg0, null);
                    break;
                case A_CLSE:
                    send(A_CLSE, id, remoteId, null);
                    return result.toString();
                default:
                    break;
            }
        }
        return result.toString();
    }

    public void close() {
        try { if (socket != null) socket.close(); } catch (IOException ignored) {}
    }

    // ===================== 协议帧 =====================

    private static final class Message {
        int command;
        int arg0;
        int arg1;
        byte[] payload;
    }

    private Message readMessage() throws IOException {
        byte[] header = new byte[HEADER_LEN];
        in.readFully(header);
        ByteBuffer bb = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
        Message m = new Message();
        m.command = bb.getInt();
        m.arg0 = bb.getInt();
        m.arg1 = bb.getInt();
        int length = bb.getInt();
        // checksum/magic 跳过
        if (length > 0) {
            m.payload = new byte[length];
            in.readFully(m.payload);
        } else {
            m.payload = new byte[0];
        }
        return m;
    }

    private void send(int command, int arg0, int arg1, byte[] payload) throws IOException {
        if (payload == null) payload = new byte[0];
        ByteBuffer bb = ByteBuffer.allocate(HEADER_LEN + payload.length)
                .order(ByteOrder.LITTLE_ENDIAN);
        bb.putInt(command);
        bb.putInt(arg0);
        bb.putInt(arg1);
        bb.putInt(payload.length);
        bb.putInt(checksum(payload));
        bb.putInt(command ^ 0xffffffff);
        bb.put(payload);
        out.write(bb.array());
        out.flush();
    }

    private static int checksum(byte[] data) {
        int sum = 0;
        for (byte b : data) sum += (b & 0xff);
        return sum;
    }

    /**
     * 编码 ADB 格式公钥（PKCS#1 RSAPublicKey DER 的 base64）。
     *
     * <p>重要：Android adbd 的 parse_key()（adb_auth.cpp）只接受两种格式——
     * PKCS#8 私钥（d2i_PKCS8_PRIV_KEY_INFO）或 PKCS#1 公钥（d2i_RSAPublicKey）。
     * <b>X.509 SubjectPublicKeyInfo（getPublic().getEncoded()）不被支持</b>，
     * 会导致 adbd 报 "Invalid base64 key"、认证永远失败。
     * 这里手工构造 PKCS#1 RSAPublicKey：SEQUENCE { INTEGER n, INTEGER e }。</p>
     */
    public static String encodePublicKey(KeyPair kp) {
        RSAPublicKey pub = (RSAPublicKey) kp.getPublic();
        byte[] der = encodePkcs1RsaPublicKey(pub.getModulus(), pub.getPublicExponent());
        return Base64.getEncoder().encodeToString(der) + " DeviceResetSpoofer";
    }

    /** 构造 PKCS#1 RSAPublicKey DER：SEQUENCE { INTEGER n, INTEGER e } */
    private static byte[] encodePkcs1RsaPublicKey(BigInteger n, BigInteger e) {
        byte[] nDer = derEncode(0x02, n.toByteArray()); // INTEGER
        byte[] eDer = derEncode(0x02, e.toByteArray()); // INTEGER
        ByteArrayOutputStream seq = new ByteArrayOutputStream();
        seq.write(nDer, 0, nDer.length);
        seq.write(eDer, 0, eDer.length);
        return derEncode(0x30, seq.toByteArray());      // SEQUENCE
    }

    /** 最小 DER 长度/TLV 编码（支持最长 65535 字节负载） */
    private static byte[] derEncode(int tag, byte[] content) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(tag);
        int len = content.length;
        if (len < 0x80) {
            out.write(len);
        } else if (len <= 0xFF) {
            out.write(0x81);
            out.write(len);
        } else {
            out.write(0x82);
            out.write((len >> 8) & 0xFF);
            out.write(len & 0xFF);
        }
        out.write(content, 0, content.length);
        return out.toByteArray();
    }
}
