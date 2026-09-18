package com.samsungcameradownloader;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.DhcpInfo;
import android.net.Network;
import android.net.wifi.WifiManager;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.*;

public class CameraClient {
    private static final int SMP_PORT = 7676;
    private static final String SSDP_ADDR = "239.255.255.250";
    private static final int SSDP_PORT = 1900;
    private static final String[] SSDP_TARGETS = new String[] {
            "upnp:rootdevice",
            "urn:schemas-upnp-org:device:MediaServer:1",
            "ssdp:all"
    };

    public String friendlyName = "", modelName = "", controlUrl = "", baseUrl = "";
    public List<FileItem> files = new ArrayList<>();

    public static class FileItem {
        String title, url, mime;
        long size;
        FileItem(String t, String u, String m, long s) { title=t; url=u; mime=m; size=s; }
    }

    public static CameraClient discover(String diagPrefix, Context ctx, Network wifiNetwork) throws Exception {
        StringBuilder diag = new StringBuilder();
        diag.append("Network Wi-Fi: ").append(wifiNetwork == null ? "null" : "OK").append("\n");

        String gw = getGatewayIp(ctx);
        String broadcast = getBroadcastIp(ctx);
        diag.append("Gateway: ").append(gw == null ? "desconocido" : gw).append("\n");
        diag.append("Broadcast: ").append(broadcast == null ? "desconocido" : broadcast).append("\n");

        try {
            CameraClient c = discoverSsdp(diag, wifiNetwork, gw, broadcast);
            if (c != null) return c;
        } catch (Exception e) {
            diag.append("SSDP falló: ").append(e).append("\n");
        }

        // Fallback muy importante para estas cámaras: si el teléfono está conectado
        // directamente al AP de la cámara, su gateway suele ser la propia cámara.
        if (gw != null) {
            String[] paths = {"smp_2_", "smp_6_", "description.xml", "rootDesc.xml", ""};
            for (String path : paths) {
                try {
                    CameraClient c = tryDirectHttp("http://" + gw + ":" + SMP_PORT + "/", path, diag, wifiNetwork);
                    if (c != null) return c;
                } catch (Exception e) {
                    diag.append("GET /" + path + " -> ").append(e).append("\n");
                }
            }
        }

        throw new IOException(diagPrefix + diag +
                "\nNo se encontró un dispositivo Samsung con ContentDirectory.");
    }

    private static String getGatewayIp(Context ctx) {
        try {
            WifiManager wm = (WifiManager) ctx.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            DhcpInfo dhcp = wm.getDhcpInfo();
            if (dhcp == null || dhcp.gateway == 0) return null;
            return intToIp(dhcp.gateway);
        } catch (Exception e) { return null; }
    }

    private static String getBroadcastIp(Context ctx) {
        try {
            WifiManager wm = (WifiManager) ctx.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            DhcpInfo d = wm.getDhcpInfo();
            if (d == null || d.ipAddress == 0 || d.netmask == 0) return null;
            int b = d.ipAddress | ~d.netmask;
            return intToIp(b);
        } catch (Exception e) { return null; }
    }

    private static String intToIp(int ip) {
        return String.format(Locale.US, "%d.%d.%d.%d",
                ip & 0xff, (ip >> 8) & 0xff, (ip >> 16) & 0xff, (ip >> 24) & 0xff);
    }

    private static CameraClient tryDirectHttp(String base, String path, StringBuilder diag, Network network) throws Exception {
        String descUrl = base + path;
        HttpURLConnection h = (HttpURLConnection) new URL(descUrl).openConnection();
        h.setConnectTimeout(1800);
        h.setReadTimeout(3000);
        h.setRequestProperty("User-Agent", "SEC_RVF_ML_00:00:00:00:00:00");
        h.setRequestProperty("Accept", "text/xml, */*");
        try {
            int code = h.getResponseCode();
            diag.append("GET ").append(descUrl).append(" -> HTTP ").append(code).append("\n");
            if (code != 200) return null;
            byte[] all = readAll(h.getInputStream());
            CameraClient c = parseDescription(all, descUrl);
            if (c != null) return c;
        } finally { h.disconnect(); }
        return null;
    }

    private static CameraClient parseDescription(byte[] all, String location) throws Exception {
        Document doc = xml(new ByteArrayInputStream(all));
        Element device = first(doc, "device");
        if (device == null) return null;
        CameraClient c = new CameraClient();
        c.friendlyName = text(device, "friendlyName");
        c.modelName = text(device, "modelName");
        c.baseUrl = new URL(location).toURI().resolve(".").toString();
        NodeList services = device.getElementsByTagNameNS("*", "service");
        for (int i=0; i<services.getLength(); i++) {
            Element s = (Element)services.item(i);
            String type = text(s, "serviceType");
            String cu = text(s, "controlURL");
            if (type.contains("ContentDirectory") && !cu.isEmpty()) {
                c.controlUrl = new URL(new URL(location), cu).toString();
            }
        }
        return c.controlUrl.isEmpty() ? null : c;
    }

    private static byte[] readAll(InputStream in) throws IOException {
        try (InputStream input = in) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192]; int n;
            while ((n=input.read(buf))!=-1) bos.write(buf,0,n);
            return bos.toByteArray();
        }
    }

    private static CameraClient discoverSsdp(StringBuilder diag, Network wifiNetwork,
                                             String gateway, String broadcast) throws Exception {
        Set<String> locations = new LinkedHashSet<>();
        int total = 0;
        for (String target : SSDP_TARGETS) {
            String msg = "M-SEARCH * HTTP/1.1\r\n" +
                    "HOST: " + SSDP_ADDR + ":" + SSDP_PORT + "\r\n" +
                    "MAN: \"ssdp:discover\"\r\n" +
                    "MX: 2\r\n" +
                    "ST: " + target + "\r\n\r\n";
            total += sendSsdp(msg, SSDP_ADDR, diag, wifiNetwork, locations);
            // Android/camera AP fallback: send the same discovery directly to the gateway
            // and subnet broadcast. This avoids relying exclusively on multicast routing.
            if (gateway != null) total += sendSsdp(msg, gateway, diag, wifiNetwork, locations);
            if (broadcast != null) total += sendSsdp(msg, broadcast, diag, wifiNetwork, locations);
        }
        diag.append("Respuestas SSDP totales: ").append(total).append("\n");
        for (String loc : locations) {
            try {
                CameraClient c = fromDescription(loc);
                if (c != null) return c;
            } catch (Exception e) {
                diag.append("LOCATION " + loc + " -> " + e + "\n");
            }
        }
        return null;
    }

    private static int sendSsdp(String msg, String destination, StringBuilder diag,
                                Network network, Set<String> locations) {
        int count=0;
        try {
            DatagramSocket sock = new DatagramSocket(null);
            sock.setReuseAddress(true);
            sock.bind(new InetSocketAddress(0));
            if (network != null) {
                try { network.bindSocket(sock); }
                catch (Exception e) { diag.append("bindSocket SSDP: ").append(e).append("\n"); }
            }
            sock.setSoTimeout(900);
            byte[] bytes=msg.getBytes(StandardCharsets.US_ASCII);
            sock.send(new DatagramPacket(bytes,bytes.length,InetAddress.getByName(destination),SSDP_PORT));
            long end=System.currentTimeMillis()+3200;
            byte[] buf=new byte[8192];
            while(System.currentTimeMillis()<end) {
                try {
                    DatagramPacket p=new DatagramPacket(buf,buf.length);
                    sock.receive(p); count++;
                    String response=new String(p.getData(),p.getOffset(),p.getLength(),StandardCharsets.UTF_8);
                    String loc=header(response,"location");
                    if(loc!=null) locations.add(loc);
                } catch(SocketTimeoutException ignored) { break; }
            }
            sock.close();
        } catch(Exception e) {
            diag.append("SSDP -> ").append(destination).append(": ").append(e).append("\n");
        }
        return count;
    }

    private static String header(String response,String wanted) {
        for(String line:response.split("\\r?\\n")) {
            int i=line.indexOf(':');
            if(i>0 && line.substring(0,i).trim().equalsIgnoreCase(wanted)) return line.substring(i+1).trim();
        }
        return null;
    }

    private static CameraClient fromDescription(String location) throws Exception {
        HttpURLConnection h=(HttpURLConnection)new URL(location).openConnection();
        h.setConnectTimeout(4000); h.setReadTimeout(6000);
        h.setRequestProperty("User-Agent","SEC_RVF_ML_00:00:00:00:00:00");
        byte[] data=readAll(h.getInputStream()); h.disconnect();
        return parseDescription(data,location);
    }

    public List<FileItem> browse() throws Exception { return browseContainer("0"); }

    private List<FileItem> browseContainer(String objectId) throws Exception {
        List<FileItem> result=new ArrayList<>();
        String soap="<?xml version=\"1.0\" encoding=\"utf-8\"?>"+
                "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body>"+
                "<u:Browse xmlns:u=\"urn:schemas-upnp-org:service:ContentDirectory:1\"><ObjectID>"+esc(objectId)+"</ObjectID>"+
                "<BrowseFlag>BrowseDirectChildren</BrowseFlag><Filter>*</Filter><StartingIndex>0</StartingIndex><RequestedCount>200</RequestedCount><SortCriteria></SortCriteria></u:Browse></s:Body></s:Envelope>";
        HttpURLConnection h=(HttpURLConnection)new URL(controlUrl).openConnection();
        h.setConnectTimeout(10000); h.setReadTimeout(15000); h.setRequestMethod("POST"); h.setDoOutput(true);
        h.setRequestProperty("Content-Type","text/xml; charset=\"utf-8\"");
        h.setRequestProperty("SOAPAction","\"urn:schemas-upnp-org:service:ContentDirectory:1#Browse\"");
        h.getOutputStream().write(soap.getBytes(StandardCharsets.UTF_8));
        Document envelope=xml(h.getInputStream());
        Element resultNode=first(envelope,"Result"); if(resultNode==null)return result;
        Document d=xml(new ByteArrayInputStream(resultNode.getTextContent().getBytes(StandardCharsets.UTF_8)));
        NodeList direct=d.getDocumentElement().getChildNodes();
        for(int i=0;i<direct.getLength();i++) {
            if(!(direct.item(i) instanceof Element)) continue;
            Element e=(Element)direct.item(i); String tag=e.getLocalName()!=null?e.getLocalName():e.getNodeName();
            if(tag.equals("container")) result.addAll(browseContainer(e.getAttribute("id")));
            else if(tag.equals("item")) {
                String title=text(e,"title"); NodeList rs=e.getElementsByTagNameNS("*","res");
                if(rs.getLength()==0)continue;
                Element best=null; long bestSize=-1;
                for(int j=0;j<rs.getLength();j++) {
                    Element r=(Element)rs.item(j); String pi=r.getAttribute("protocolInfo");
                    if(pi.contains("JPEG_TN")||pi.contains("JPEG_SM")||pi.contains("PNG_TN")||pi.contains("PNG_SM")||pi.contains("_TN")||pi.contains("_SM"))continue;
                    long size=0; try{size=Long.parseLong(r.getAttribute("size"));}catch(Exception ignored){}
                    if(size>bestSize){best=r;bestSize=size;}
                }
                if(best==null)best=(Element)rs.item(0);
                result.add(new FileItem(title,best.getTextContent().trim(),mime(best.getAttribute("protocolInfo")),bestSize));
            }
        }
        return result;
    }

    private static String mime(String pi){String[] p=pi.split(":");return p.length>=3?p[2]:"";}

    public String downloadToMediaStore(Context ctx,FileItem item)throws Exception{
        String name=safeName(item.title);
        if(!name.contains(".")){
            if(item.mime.equalsIgnoreCase("image/jpeg"))name+=".jpg";
            else if(item.mime.equalsIgnoreCase("video/mp4"))name+=".mp4";
        }
        String mime=item.mime.isEmpty()?"application/octet-stream":item.mime;
        ContentResolver cr=ctx.getContentResolver();
        android.net.Uri collection=mime.startsWith("video/")
                ? MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                : MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
        ContentValues v=new ContentValues();
        v.put(MediaStore.MediaColumns.DISPLAY_NAME,name);
        v.put(MediaStore.MediaColumns.MIME_TYPE,mime);
        v.put(MediaStore.MediaColumns.RELATIVE_PATH,Environment.DIRECTORY_DCIM+"/Samsung Camera");
        v.put(MediaStore.MediaColumns.IS_PENDING,1);
        android.net.Uri uri=cr.insert(collection,v); if(uri==null)throw new IOException("No se pudo crear: "+name);
        try{
            HttpURLConnection h=(HttpURLConnection)new URL(item.url).openConnection(); h.setConnectTimeout(10000);h.setReadTimeout(60000);
            try(InputStream in=h.getInputStream();OutputStream out=cr.openOutputStream(uri)){
                byte[] buf=new byte[65536];int n;while((n=in.read(buf))!=-1)out.write(buf,0,n);
            }
            ContentValues done=new ContentValues();done.put(MediaStore.MediaColumns.IS_PENDING,0);cr.update(uri,done,null,null);return name;
        }catch(Exception e){cr.delete(uri,null,null);throw e;}
    }

    private static String safeName(String s){s=s.replaceAll("[\\\\/:*?\"<>|]","_").trim();return s.isEmpty()?"camera_file":s;}
    private static String esc(String s){return s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;");}
    private static Document xml(InputStream in)throws Exception{
        DocumentBuilderFactory f=DocumentBuilderFactory.newInstance();f.setNamespaceAware(true);
        try{f.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);}catch(Exception ignored){}
        return f.newDocumentBuilder().parse(in);
    }
    private static Element first(Document d,String name){NodeList n=d.getElementsByTagNameNS("*",name);return n.getLength()>0?(Element)n.item(0):null;}
    private static String text(Element e,String name){NodeList n=e.getElementsByTagNameNS("*",name);return n.getLength()>0?n.item(0).getTextContent().trim():"";}
}
