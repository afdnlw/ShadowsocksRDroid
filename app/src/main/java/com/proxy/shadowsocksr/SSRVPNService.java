package com.proxy.shadowsocksr;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.AssetManager;
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.net.VpnService;
import android.os.Build;
import android.os.IBinder;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.system.OsConstants;

import com.proxy.shadowsocksr.items.ConnectProfile;
import com.proxy.shadowsocksr.items.GlobalProfile;
import com.proxy.shadowsocksr.items.SSProfile;
import com.proxy.shadowsocksr.util.ConfFileUtil;
import com.proxy.shadowsocksr.util.DNSUtil;
import com.proxy.shadowsocksr.util.InetAddressUtil;
import com.proxy.shadowsocksr.util.ShellUtil;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SSRVPNService extends VpnService
{
    private int VPN_MTU = 1500;
    private String PRIVATE_VLAN = "27.27.27.%s";
    private String PRIVATE_VLAN6 = "fdfe:dcba:9875::%s";
    private ParcelFileDescriptor conn;

    private String session;
    private SSProfile ssProfile;
    private GlobalProfile globalProfile;
    private List<String> proxyApps;
    private SSRVPNThread vpnThread;

    private volatile boolean isVPNConnected = false;

    private ISSRServiceCallback callback = null;

    private SSRService binder = new SSRService();

    @Override public IBinder onBind(Intent intent)
    {
        return binder;
    }

    @Override public void onRevoke()
    {
        if (vpnThread != null)
        {
            vpnThread.stopThread();
            vpnThread = null;
        }

        isVPNConnected = false;

        try
        {
            callback.onStatusChanged(Consts.STATUS_DISCONNECTED);
        }
        catch (RemoteException ignored)
        {
        }

        stopRunner();
    }

    class SSRService extends ISSRService.Stub
    {
        @Override public boolean onTransact(int code, Parcel data, Parcel reply, int flags)
        throws RemoteException
        {   //if user use system dialog close vpn,onRevoke will not called
            if (code == IBinder.LAST_CALL_TRANSACTION)
            {
                onRevoke();
                return true;
            }
            return super.onTransact(code, data, reply, flags);
        }

        @Override public boolean status() throws RemoteException
        {
            return isVPNConnected;
        }

        @Override public void registerISSRServiceCallBack(ISSRServiceCallback cb)
        throws RemoteException
        {
            callback = cb;
        }

        @Override public void unRegisterISSRServiceCallBack(ISSRServiceCallback cb)
        throws RemoteException
        {
            callback = null;
        }

        @Override public void start(ConnectProfile cp) throws RemoteException
        {
            session = cp.label;
            ssProfile = new SSProfile(cp.server, cp.remotePort, cp.localPort, cp.cryptMethod,
                                      cp.passwd);
            globalProfile = new GlobalProfile(cp.route, cp.globalProxy, cp.dnsForward,
                                              cp.autoConnect);
            if (!cp.globalProxy)
            {
                proxyApps = cp.proxyApps;
            }
            checkDaemonFile();
            startRunner();
        }

        @Override public void stop() throws RemoteException
        {
            onRevoke();
        }
    }

    public void checkDaemonFile()
    {
        for (String fn : new String[]{"pdnsd", "redsocks", "ss-local", "ss-tunnel", "tun2socks"})
        {
            File f = new File(Consts.baseDir + fn);
            if (f.exists())
            {
                if (!f.canRead() || !f.canExecute())
                {
                    ShellUtil.runCmd("chmod 755 " + f.getAbsolutePath());
                }
            }
            else
            {
                copyDaemonBin(fn, f);
                ShellUtil.runCmd("chmod 755 " + f.getAbsolutePath());
            }
        }

    }

    private void copyDaemonBin(String file, File out)
    {
        AssetManager am = getAssets();
        String abi = Jni.getABI();
        byte[] buf = new byte[4096];
        try
        {
            boolean create = out.createNewFile();
            if (!create)
            {
                throw new IOException("Create File Failed!");
            }
            InputStream is = am.open(abi + File.separator + file);
            FileOutputStream fos = new FileOutputStream(out);
            int length = is.read(buf);
            while (length > 0)
            {
                fos.write(buf, 0, length);
                length = is.read(buf);
            }
            fos.flush();
            fos.close();
            is.close();
        }
        catch (IOException e)
        {
            onRevoke();
        }
    }

    private void startRunner()
    {
        vpnThread = new SSRVPNThread();
        vpnThread.start();
        //
        killProcesses();
        //
        new Thread(new Runnable()
        {
            @Override public void run()
            {
                if (!InetAddressUtil.isIPv4Address(ssProfile.server) &&
                    !InetAddressUtil.isIPv6Address(ssProfile.server))
                {
                    DNSUtil du = new DNSUtil();
                    String ip = du.resolve(ssProfile.server, true);
                    if (ip == null)
                    {
                        stopRunner();
                        try
                        {
                            callback.onStatusChanged(Consts.STATUS_FAILED);
                        }
                        catch (Exception ignored)
                        {//Ignore remote exception and null point exception
                        }
                        return;
                    }
                    ssProfile.server = ip;
                }
                //
                startSSRDaemon();
                if (!globalProfile.dnsForward)
                {
                    startDnsDaemon();
                    startDnsTunnel();
                }
                //
                int fd = startVpn();
                if (fd != -1)
                {
                    int tries = 1;
                    while (tries < 5)
                    {
                        try
                        {
                            Thread.sleep(1000 * tries);
                        }
                        catch (InterruptedException e)
                        {
                            e.printStackTrace();
                        }
                        if (Jni.sendFd(fd) != -1)
                        {
                            isVPNConnected = true;
                            try
                            {
                                callback.onStatusChanged(Consts.STATUS_CONNECTED);
                            }
                            catch (Exception ignored)
                            {//Ignore remoteexcetpion and nullpoint
                            }
                            return;
                        }
                        tries++;
                    }
                }
                stopRunner();
                try
                {
                    callback.onStatusChanged(Consts.STATUS_FAILED);
                }
                catch (Exception ignored)
                {//Ignore remoteexcetpion and nullpoint
                }
            }
        }).start();
    }

    private void stopRunner()
    {
        isVPNConnected = false;
        if (vpnThread != null)
        {
            vpnThread.stopThread();
            vpnThread = null;
        }

        //reset
        killProcesses();

        //close conn
        if (conn != null)
        {
            try
            {
                conn.close();
            }
            catch (IOException ignored)
            {
            }
            conn = null;
        }
        ////////////////
        //stop service
        if (callback == null)
        {
            stopSelf();
        }
    }

    private void startSSRDaemon()
    {
        String[] acl = new String[0];
        switch (globalProfile.route)
        {
        case "bypass-lan":
            acl = getResources().getStringArray(R.array.private_route);
            break;
        case "bypass-lan-and-list":
            acl = getResources().getStringArray(R.array.lst_route_full);
            break;
        }

        StringBuilder s = new StringBuilder();
        for (String a : acl)
        {
            s.append(a).append(Consts.lineSept);
        }
        ConfFileUtil.writeToFile(s.toString(), new File(Consts.baseDir + "acl.list"));

        String ssrconf = String.format(ConfFileUtil.SSRConf,
                                       ssProfile.server,
                                       ssProfile.remotePort,
                                       ssProfile.localPort,
                                       ssProfile.passwd,
                                       ssProfile.cryptMethod,
                                       20);
        ConfFileUtil.writeToFile(ssrconf, new File(Consts.baseDir + "ss-local-vpn.conf"));

        StringBuilder sb = new StringBuilder();
        sb.append(
                Consts.baseDir + "ss-local -V -u -b 127.0.0.1 -t 600 -c " + Consts.baseDir +
                "ss-local-vpn.conf -f " + Consts.baseDir +
                "ss-local-vpn.pid");

        //AUTH

        if (!globalProfile.route.equals("all"))
        {
            sb.append(" --acl").append(Consts.baseDir + "acl.list");
        }

        ShellUtil.runCmd(sb.toString());
    }

    private void startDnsTunnel()
    {
        String ssrconf = String.format(ConfFileUtil.SSRConf, ssProfile.server,
                                       ssProfile.remotePort, 8163, ssProfile.passwd,
                                       ssProfile.cryptMethod, 10);
        ConfFileUtil.writeToFile(ssrconf, new File(Consts.baseDir + "ss-tunnel-vpn.conf"));

        //AUTH

        ShellUtil.runCmd((Consts.baseDir +
                          "ss-tunnel -V -u -b 127.0.0.1 -t 10 -l 8163 -L 8.8.8.8:53 -c " +
                          Consts.baseDir + "ss-tunnel-vpn.conf -f " + Consts.baseDir +
                          "ss-tunnel-vpn.pid"));
    }

    private void startDnsDaemon()
    {
        String pdnsd;
        //ipv6 config
        if (globalProfile.route.equals("bypass-lan-and-list"))
        {
            String reject = getResources().getString(R.string.reject);
            String blklst = getResources().getString(R.string.black_list);

            pdnsd = String.format(ConfFileUtil.PdNSdDirect, "0.0.0.0", 8153,
                                  Consts.baseDir + "pdnsd-vpn.pid", reject, blklst, 8163, "");//IPV6
        }
        else
        {
            pdnsd = String.format(ConfFileUtil.PDNSdLocal, "0.0.0.0", 8153,
                                  Consts.baseDir + "pdnsd-vpn.pid", 8163, "");//IPV6
        }

        ConfFileUtil.writeToFile(pdnsd, new File(Consts.baseDir + "pdnsd-vpn.conf"));

        String cmd = Consts.baseDir + "pdnsd -c " + Consts.baseDir + "pdnsd-vpn.conf";
        ShellUtil.runCmd(cmd);
    }

    private int startVpn()
    {
        Builder builder = new Builder();
        builder.setSession(session)
               .setMtu(VPN_MTU)
               .addAddress(String.format(PRIVATE_VLAN, "1"), 24)
               .addDnsServer("8.8.8.8");

        builder.addAddress(String.format(PRIVATE_VLAN6, "1"), 126);
        builder.addRoute("::", 0);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP)
        {
            builder.allowFamily(OsConstants.AF_INET6);

            if (!globalProfile.globalProxy)
            {
                for (String pkg : proxyApps)
                {
                    try
                    {
                        builder.addAllowedApplication(pkg);
                    }
                    catch (PackageManager.NameNotFoundException ignored)
                    {
                    }
                }
            }
        }

        if (globalProfile.route.equals("all"))
        {
            builder.addRoute("0.0.0.0", 0);
        }
        else
        {
            String[] privateLst = getResources().getStringArray(R.array.bypass_private_route);
            for (String p : privateLst)
            {
                String[] sp = p.split("/");
                builder.addRoute(sp[0], Integer.valueOf(sp[1]));
            }
        }

        builder.addRoute("8.8.0.0", 16);

        try
        {
            conn = builder.establish();
        }
        catch (Exception e)
        {
            conn = null;
        }

        if (conn == null)
        {
            return -1;
        }

        int fd = conn.getFd();

        String cmd = String.format(Consts.baseDir +
                                   "tun2socks --netif-ipaddr %s"
                                   + " --netif-netmask 255.255.255.0"
                                   + " --socks-server-addr 127.0.0.1:%d"
                                   + " --tunfd %d"
                                   + " --tunmtu %d"
                                   + " --loglevel 0"
                                   + " --pid %stun2socks-vpn.pid",
                                   String.format(PRIVATE_VLAN, "2"),
                                   ssProfile.localPort, fd, VPN_MTU, Consts.baseDir);

        cmd += " --netif-ip6addr " + String.format(PRIVATE_VLAN6, "2");

        if (globalProfile.dnsForward)
        {
            cmd += " --enable-udprelay";
        }
        else
        {
            cmd += String.format(" --dnsgw %s:8153", String.format(PRIVATE_VLAN, "1"));
        }

        ShellUtil.runCmd(cmd);

        return fd;
    }

    @SuppressWarnings("ResultOfMethodCallIgnored") private void killProcesses()
    {
        String[] tasks = new String[]{"ss-local", "ss-tunnel", "pdnsd", "tun2socks"};
        List<String> cmds = new ArrayList<>();

        for (String task : tasks)
        {
            cmds.add(String.format("chmod 666 %s%s-vpn.pid", Consts.baseDir, task));
        }
        String[] cmdarr = new String[cmds.size()];
        cmds.toArray(cmdarr);
        ShellUtil.runCmd(cmdarr);
        cmds.clear();

        for (String t : tasks)
        {
            try
            {
                File pidf = new File(Consts.baseDir + t + "-vpn.pid");
                int pid = new Scanner(pidf).useDelimiter("\\Z").nextInt();
                android.os.Process.killProcess(pid);
                pidf.delete();
            }
            catch (Exception ignored)
            {
            }
            cmds.add(String.format("rm -f %s%s-vpn.conf", Consts.baseDir, t));
        }
        cmdarr = new String[cmds.size()];
        cmds.toArray(cmdarr);
        ShellUtil.runCmd(cmdarr);
    }

    //    private boolean isByPass(SubnetUtils net)
    //    {
    //        return net.getInfo().isInRange(cfg.server);
    //    }
    //
    //    private boolean isPrivateA(int a)
    //    {
    //        return a == 10 || a == 192 || a == 172;
    //    }
    //
    //    private boolean isPrivateB(int a, int b)
    //    {
    //        return a == 10 || (a == 192 && b == 168) || (a == 172 && b >= 16 && b < 32);
    //    }

    class SSRVPNThread extends Thread
    {
        volatile private LocalServerSocket lss;
        volatile private boolean isRunning = true;

        @SuppressWarnings("ResultOfMethodCallIgnored") @Override public void run()
        {
            try
            {
                new File(Consts.baseDir + "protect_path").delete();
                LocalSocket ls = new LocalSocket();
                ls.bind(new LocalSocketAddress(Consts.baseDir + "protect_path",
                                               LocalSocketAddress.Namespace.FILESYSTEM));
                lss = new LocalServerSocket(ls.getFileDescriptor());
            }
            catch (IOException e)
            {
                return;
            }

            ExecutorService exec = Executors.newFixedThreadPool(4);

            while (isRunning)
            {
                try
                {
                    final LocalSocket ls = lss.accept();
                    exec.execute(new Runnable()
                    {
                        @Override public void run()
                        {
                            try
                            {
                                InputStream is = ls.getInputStream();
                                OutputStream os = ls.getOutputStream();

                                is.read();

                                FileDescriptor[] fds = ls.getAncillaryFileDescriptors();

                                if (fds.length > 0)
                                {
                                    Method getInt = FileDescriptor.class
                                            .getDeclaredMethod("getInt$");
                                    Integer fd = (Integer) getInt.invoke(fds[0]);
                                    boolean ret = protect(fd);

                                    Jni.jniClose(fd);

                                    if (ret)
                                    {
                                        os.write(0);
                                    }
                                    else
                                    {
                                        os.write(1);
                                    }

                                    is.close();
                                    os.close();
                                    ls.close();
                                }
                            }
                            catch (InvocationTargetException | IOException | IllegalAccessException | NoSuchMethodException e)
                            {
                                e.printStackTrace();
                            }
                        }
                    });
                }
                catch (IOException e)
                {
                    return;
                }
            }
        }

        private void closeServerSocket()
        {
            if (lss != null)
            {
                try
                {
                    lss.close();
                }
                catch (IOException ignored)
                {
                }
                lss = null;
            }
        }

        private void stopThread()
        {
            isRunning = false;
            closeServerSocket();
        }
    }
}
