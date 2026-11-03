package com.local.gpttodo;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;

/** Local authenticated CONNECT transport. TLS stays end-to-end with OpenAI. */
public final class AndroidNetworkProxy implements AutoCloseable {
    private final ServerSocket listener;
    private final ExecutorService pool=Executors.newFixedThreadPool(12,r->{Thread t=new Thread(r,"page-network");t.setDaemon(true);return t;});
    private final String password=UUID.randomUUID().toString().replace("-","");
    private final String header="Basic "+Base64.getEncoder().encodeToString(("widget:"+password).getBytes(StandardCharsets.US_ASCII));
    private final Set<Socket> sockets=ConcurrentHashMap.newKeySet();
    private volatile boolean closed;
    private final java.util.concurrent.atomic.AtomicInteger acceptedConnections=new java.util.concurrent.atomic.AtomicInteger();
    public AndroidNetworkProxy() throws IOException {
        listener=new ServerSocket(0,8,InetAddress.getByName("127.0.0.1"));
        Thread accept=new Thread(()->{while(!closed)try{Socket s=listener.accept();sockets.add(s);pool.submit(()->handle(s));}catch(Exception ignored){if(closed)break;}},"page-proxy-accept");
        accept.setDaemon(true);accept.start();
    }
    public String url(){return "http://widget:"+password+"@127.0.0.1:"+listener.getLocalPort();}
    public int acceptedConnections(){return acceptedConnections.get();}
    private static boolean allowed(String host){return host.equals("openai.com")||host.endsWith(".openai.com")||host.equals("chatgpt.com")||host.endsWith(".chatgpt.com");}
    private void handle(Socket client){Socket upstream=null;
        try{
            client.setSoTimeout(10000);ByteArrayOutputStream bytes=new ByteArrayOutputStream();int state=0;
            while(bytes.size()<16384){int b=client.getInputStream().read();if(b<0)return;bytes.write(b);state=state==0&&b==13?1:state==1&&b==10?2:state==2&&b==13?3:state==3&&b==10?4:0;if(state==4)break;}
            String[] lines=bytes.toString("ISO-8859-1").split("\r\n");String auth="";
            for(String line:lines)if(line.toLowerCase(Locale.ROOT).startsWith("proxy-authorization:"))auth=line.substring(line.indexOf(':')+1).trim();
            if(!MessageDigest.isEqual(auth.getBytes(StandardCharsets.US_ASCII),header.getBytes(StandardCharsets.US_ASCII))){reply(client,"407 Proxy Authentication Required\r\nProxy-Authenticate: Basic realm=\"widget\"");return;}
            String[] first=lines[0].split(" ");if(first.length!=3||!first[0].equals("CONNECT")){reply(client,"405 Method Not Allowed");return;}
            int colon=first[1].lastIndexOf(':');if(colon<=0||!first[1].substring(colon+1).equals("443")){reply(client,"403 Forbidden");return;}
            String host=first[1].substring(0,colon).toLowerCase(Locale.ROOT);
            if(!allowed(host)){reply(client,"403 Forbidden");return;}
            upstream=new Socket();sockets.add(upstream);upstream.connect(new InetSocketAddress(host,443),12000);upstream.setSoTimeout(120000);client.setSoTimeout(120000);
            acceptedConnections.incrementAndGet();
            client.getOutputStream().write("HTTP/1.1 200 Connection Established\r\n\r\n".getBytes(StandardCharsets.US_ASCII));client.getOutputStream().flush();
            Socket remote=upstream;Future<?> outgoing=pool.submit(()->copy(client,remote));copy(remote,client);outgoing.cancel(true);
        }catch(Exception ignored){}finally{closeSocket(client);if(upstream!=null)closeSocket(upstream);}
    }
    private void reply(Socket socket,String status)throws IOException{socket.getOutputStream().write(("HTTP/1.1 "+status+"\r\nContent-Length: 0\r\n\r\n").getBytes(StandardCharsets.US_ASCII));}
    private void copy(Socket from,Socket to){try{byte[] buffer=new byte[16384];int n;while((n=from.getInputStream().read(buffer))!=-1){to.getOutputStream().write(buffer,0,n);to.getOutputStream().flush();}}catch(IOException ignored){}finally{try{to.shutdownOutput();}catch(Exception ignored){}}}
    private void closeSocket(Socket s){sockets.remove(s);try{s.close();}catch(IOException ignored){}}
    @Override public void close(){closed=true;try{listener.close();}catch(IOException ignored){}for(Socket s:sockets)closeSocket(s);pool.shutdownNow();}
}
