package org.jboss.netty.example.xnd;

/**
 * Created by yuanyuanming on 14-4-30.
 */
public class XndClient {

    public static void main(String[] args) {
        String msg=null;
        int count=  Integer.parseInt(msg);
        StringBuffer sb=new StringBuffer();
        while (count>0){
            sb.append("a");
            count--;
        }
        sb.toString();

    }
}
