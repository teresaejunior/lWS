/*
 * Copyright (C) 2017 Mikhail Basov
 * Copyright (C) 2009-2014 Markus Bode
 * 
 * Licensed under the GNU General Public License v3
 * 
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 */

package net.basov.lws;

import android.content.Context;
import android.content.res.AssetManager;
import android.os.Handler;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.UnsupportedEncodingException;
import java.net.Socket;
import java.net.URLEncoder;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;

import static net.basov.lws.Constants.*;

class ServerHandler extends Thread {
    private final Socket toClient;
    private final String documentRoot;
    private final Context context;
    private static Handler msgHandler;

    public ServerHandler(String d, Context c, Socket s, Handler h) {
        toClient = s;
        documentRoot = d;
        context = c;
        msgHandler = h;
    }

    public void run() {
        String document = "";

        try {
            BufferedReader in = new BufferedReader(new InputStreamReader(toClient.getInputStream()));

            // Receive data
            while (true) {
                String s = in.readLine().trim();
                    if (s.equals("")) {
                    break;
                }

                if (s.substring(0, 3).equals("GET")) {
                    int leerstelle = s.indexOf(" HTTP/");
                    document = s.substring(5,leerstelle);
                    document = document.replaceAll("[/]+","/");
                    document = URLDecoder.decode(document, "UTF-8");
                }
              }
        } catch (Exception e) {
            Server.remove(toClient);
            try {
                toClient.close();
            }
            catch (Exception ex){}
        }
        showHtml(document);
    }

    private void send(String text) {
        String header = context.getString(R.string.header,
                context.getString(R.string.rc200),
                text.length(),
                "text/html"
        );
        try {
            PrintWriter out = new PrintWriter(toClient.getOutputStream(), true);
            out.print(header);
            out.print(text);
            out.flush();
            Server.remove(toClient);
            toClient.close();
        } catch (Exception e) {

        }
    }

    private void showHtml(String document) {
        Integer rc = 200;
        Long fileSize = 0L;
        String clientIP = "";
        if(toClient != null
                && toClient.getRemoteSocketAddress() != null
                && toClient.getRemoteSocketAddress().toString() != null
                && toClient.getRemoteSocketAddress().toString().length() > 2
                ) {
            clientIP = toClient.getRemoteSocketAddress().toString().substring(1);
            Integer clientIPColon = clientIP.indexOf(':');
            if (clientIPColon > 0)
                clientIP = clientIP.substring(0, clientIPColon);
        }

        // Standard-Doc
        if (document.equals("")) {
            document = "/";
        }

        // Don't allow directory traversal
        if (document.contains("..")) {
            rc = 403;
        }

        // Search for files in document root
        document = documentRoot + document;
        document = document.replaceAll("[/]+","/");

        try {
            if (!new File(document).exists()) {
                rc = 404;
            } else if(document.charAt(document.length()-1) == '/') {
                // This is directory
                if (new File(document+"index.html").exists()) {
                    document = document + "index.html";
                } else {
                    send(directoryHTMLindex(document));
                    StartActivity.putToLogScreen(
                            "rc: "
                                    + rc
                                    + ", "
                                    + clientIP
                                    + ", /"
                                    + document.replace(documentRoot, "")
                                    + " (dir. index)",
                            msgHandler
                    );
                    return;
                }
            }

        } catch (Exception e) {}

        try {
            String rcStr;
            String header;
            String contType;
            BufferedOutputStream outStream = new BufferedOutputStream(toClient.getOutputStream());
            BufferedInputStream in;

            if (rc == 200) {
                in = new BufferedInputStream(new FileInputStream(document));
                rcStr = context.getString(R.string.rc200);
                contType = getMIMETypeForDocument(document);
            } else {
                String errAsset = "";
                AssetManager am = context.getAssets();
                switch (rc) {
                    case 404:
                        rcStr = context.getString(R.string.rc404);
                        errAsset = "404.html";
                        break;
                    case 403:
                        rcStr = context.getString(R.string.rc403);
                        errAsset = "403.html";
                        break;
                    case 416:
                        errAsset = "416.html";
                        rcStr = context.getString(R.string.rc416);
                        break;
                    default:
                        errAsset = "500.html";
                        rcStr = context.getString(R.string.rc500);
                        break;
                }

                contType = "text/html";
                in = new BufferedInputStream(am.open(errAsset));
                fileSize = Long.valueOf(in.available());

            }
            StartActivity.putToLogScreen(
                    "rc: "
                            + rc
                            + ", "
                            + clientIP
                            + ", /"
                            + document.replace(documentRoot, ""),
                    msgHandler
            );
            // If fileSize not 0 some error detected and fileSize already set
            // to assets file length
            if (fileSize == 0L) fileSize = new File(document).length();
            header = context.getString(R.string.header,
                    rcStr,
                    fileSize,
                    contType
            );

            outStream.write(header.getBytes());
            byte[] fileBuffer = new byte[4096];
            int bytesCount = 0;
            while ((bytesCount = in.read(fileBuffer)) != -1){
                outStream.write(fileBuffer, 0, bytesCount);
            }
            outStream.flush();

            Server.remove(toClient);
            toClient.close();
        } catch (Exception e) {}
    }

    private String directoryHTMLindex(String dir) {     
        String html = context.getString(
                R.string.dir_list_top_html,
                "Index of " + dir.replace(documentRoot,""),
                "Index of " + dir.replace(documentRoot,"")
        );
        
        ArrayList <String> dirs = new ArrayList<String>();
        ArrayList <String> files = new ArrayList<String>();

        for (File i : new File(dir).listFiles()) {
            if (i.isDirectory()) {
                dirs.add(i.getName());
            } else if (i.isFile()) {
                files.add(i.getName());              
            }          
        }
        
        Comparator<String> strCmp =  new Comparator<String>(){
            @Override
            public int compare(String text1, String text2)
            {
                return text1.compareToIgnoreCase(text2);
            }
        };
        
        Collections.sort(dirs, strCmp);
        Collections.sort(files, strCmp);
        
        for (String s : dirs) {
            html += context.getString(
                            R.string.dir_list_item,
                            "folder",
                            fileName2URL(s) + "/",
                            s + "/"
                    );
        }
        for (String s : files) {
            html += context.getString(
                            R.string.dir_list_item,
                            "file",
                            fileName2URL(s),
                             s
                     );
        }
        
        html += context.getString(R.string.dir_list_bottom_html);
        
        return html;
    }

    private String getMIMETypeForDocument(String document) {
        final HashMap<String,String> MIME = new HashMap<String, String>(){
            {
                put("html","text/html; charset=utf-8");
                put("css", "text/css; charset=utf-8");
                put("js", "text/javascript; charset=utf-8");
                put("txt","text/plain; charset=utf-8");
                put("md","text/markdown; charset=utf-8");
                put("gif", "image/gif");
                put("png", "image/png");
                put("jpg","image/jpeg");
                put("bmp","image/bmp");
                put("svg","image/svg+xml");
                put("zip","application/zip");
                put("gz","application/gzip");
                put("tgz","application/gzip");
                put("pdf","application/pdf");
            }
        };
        String fileExt = document.substring(
                document.lastIndexOf(".")+1
        ).toLowerCase();
        if (MIME.containsKey(fileExt))
            return MIME.get(fileExt);
        else
            return "application/octet-stream";
    }
    
    private String fileName2URL(String fn) {
        String ref = "";
        try {
            ref = URLEncoder.encode(fn, "UTF-8").replace("+", "%20");
        } catch (UnsupportedEncodingException e) {}
        return ref;
    }

}
