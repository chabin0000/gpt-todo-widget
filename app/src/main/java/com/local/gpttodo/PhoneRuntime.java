package com.local.gpttodo;

import android.content.Context;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.util.Base64;
import javax.net.ssl.*;
import org.json.JSONObject;

/** Runs the official connector runtime inside this app's private Android process context. */
public final class PhoneRuntime implements AutoCloseable {
    public final ConnectorRpc rpc;
    private final AndroidNetworkProxy network;
    public final String directory;
    public PhoneRuntime(Context context, ConnectorRpc.Events events) throws Exception {
        File executable=new File(context.getApplicationInfo().nativeLibraryDir,"libcodex_runtime.so");
        if(!executable.isFile())throw new IOException("이 기기용 연결 런타임이 없어요. ARM64 설치 파일을 확인해 주세요.");
        File data=new File(context.getFilesDir(),"page-connection");
        if(!data.isDirectory()&&!data.mkdirs())throw new IOException("연결 저장소를 만들 수 없어요.");
        directory=data.getAbsolutePath();
        File trust=new File(data,"system-ca.pem");writeCertificates(trust);
        network=new AndroidNetworkProxy();
        ConnectorRpc candidate=null;
        try {
            ProcessBuilder child=new ProcessBuilder(executable.getAbsolutePath(),"-c","features.apps=true","-c","cli_auth_credentials_store=\"file\"","app-server","--stdio");
            child.directory(data);
            // This is the runtime's documented private configuration directory, never desktop credentials.
            child.environment().put("CODEX_HOME",directory);
            child.environment().put("SSL_CERT_FILE",trust.getAbsolutePath());
            child.environment().put("HTTPS_PROXY",network.url());
            child.environment().put("HTTP_PROXY",network.url());
            child.environment().put("NO_PROXY","127.0.0.1,localhost,::1");
            child.environment().put("TMPDIR",context.getCacheDir().getAbsolutePath());
            candidate=new ConnectorRpc(child,events);candidate.initialize();rpc=candidate;
        } catch(Exception error){if(candidate!=null)candidate.close();network.close();throw error;}
    }
    private static void writeCertificates(File target) throws Exception {
        TrustManagerFactory factory=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());factory.init((KeyStore)null);
        try(Writer writer=new OutputStreamWriter(new FileOutputStream(target),StandardCharsets.US_ASCII)){
            for(TrustManager manager:factory.getTrustManagers())if(manager instanceof X509TrustManager)
                for(X509Certificate cert:((X509TrustManager)manager).getAcceptedIssuers()){
                    writer.write("-----BEGIN CERTIFICATE-----\n");
                    writer.write(Base64.getMimeEncoder(64,new byte[]{10}).encodeToString(cert.getEncoded()));
                    writer.write("\n-----END CERTIFICATE-----\n");
                }
        }
    }
    public boolean signedIn() throws Exception {
        JSONObject result=rpc.call("account/read",new JSONObject().put("refreshToken",true),30);
        JSONObject account=result.optJSONObject("account");
        return account!=null && "chatgpt".equals(account.optString("type"));
    }
    @Override public void close(){rpc.close();network.close();}
}
