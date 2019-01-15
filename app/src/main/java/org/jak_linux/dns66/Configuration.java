/* Copyright (C) 2016 Julian Andres Klode <jak@jak-linux.org>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package org.jak_linux.dns66;

import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.util.Log;

import com.google.gson.Gson;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/**
 * Configuration class. This is serialized as JSON using read() and write() methods.
 *
 * @author Julian Andres Klode
 */
public class Configuration {
    public static final Gson GSON = new Gson();
    static final int VERSION = 1;
    /* Default tweak level */
    static final int MINOR_VERSION = 1;
    public int version = 1;
    public int minorVersion = 0;
    public boolean autoStart;
    public Hosts hosts = new Hosts();
    public DnsServers dnsServers = new DnsServers();
    public Whitelist whitelist = new Whitelist();
    public boolean showNotification = true;
    public boolean nightMode;
    public boolean watchDog = false;
    public boolean ipV6Support = true;

    public static Configuration read(Reader reader) throws IOException {
        Configuration config = GSON.fromJson(reader, Configuration.class);

        if (config.whitelist.items.isEmpty()) {
            config.whitelist = new Whitelist();
            config.whitelist.items.add("com.android.vending");
        }

        if (config.version > VERSION)
            throw new IOException("Unhandled file format version");

        for (int i = config.minorVersion + 1; i <= MINOR_VERSION; i++) {
            Log.i("Configuration", String.format("read: Update to version %d", i));
            config.runUpdate(i);
        }

        return config;
    }

    public void runUpdate(int level) {
        switch (level) {
            case 1:
                updateURL("http://someonewhocares.org/hosts/hosts", "https://someonewhocares.org/hosts/hosts");
                removeURL("http://winhelp2002.mvps.org/hosts.txt");
                updateDNS("85.214.20.141", "46.182.19.48");
                break;
        }
        this.minorVersion = level;
    }

    public void updateURL(String oldURL, String newURL) {
        for (Item host : hosts.items) {
            if (host.location.equals(oldURL))
                host.location = newURL;
        }
    }

    public void updateDNS(String oldIP, String newIP) {
        for (Item host : dnsServers.items) {
            if (host.location.equals(oldIP))
                host.location = newIP;
        }
    }

    public void removeURL(String oldURL) {

        Iterator itr = hosts.items.iterator();
        while (itr.hasNext()) {
            Item host = (Item) itr.next();
            if (host.location.equals(oldURL))
                itr.remove();
        }
    }

    public void write(Writer writer) throws IOException {
        GSON.toJson(this, writer);
    }

    public static class Item {
        public static final int STATE_IGNORE = 2;
        public static final int STATE_DENY = 0;
        public static final int STATE_ALLOW = 1;
        public String title;
        public String location;
        public int state;

        public boolean isDownloadable() {
            return location.startsWith("https://") || location.startsWith("http://");
        }
    }

    public static class Hosts {
        public boolean enabled;
        public boolean automaticRefresh = false;
        public List<Item> items = new ArrayList<>();
    }

    public static class DnsServers {
        public boolean enabled;
        public List<Item> items = new ArrayList<>();
    }

    public static class Whitelist {
        /**
         * All apps use the VPN.
         */
        public static final int DEFAULT_MODE_ON_VPN = 0;
        /**
         * No apps use the VPN.
         */
        public static final int DEFAULT_MODE_NOT_ON_VPN = 1;
        /**
         * System apps (excluding browsers) do not use the VPN.
         */
        public static final int DEFAULT_MODE_INTELLIGENT = 2;

        public boolean showSystemApps;
        /**
         * The default mode to put apps in, that are not listed in the lists.
         */
        public int defaultMode = DEFAULT_MODE_ON_VPN;
        /**
         * Apps that should not be allowed on the VPN
         */
        public List<String> items = new ArrayList<>();
        /**
         * Apps that should be on the VPN
         */
        public List<String> itemsOnVpn = new ArrayList<>();

        /**
         * Categorizes all packages in the system into "on vpn" or
         * "not on vpn".
         *
         * @param pm       A {@link PackageManager}
         * @param onVpn    names of packages to use the VPN
         * @param notOnVpn Names of packages not to use the VPN
         */
        public void resolve(PackageManager pm, Set<String> onVpn, Set<String> notOnVpn) {
            Set<String> webBrowserPackageNames = new HashSet<String>();
            List<ResolveInfo> resolveInfoList = pm.queryIntentActivities(newBrowserIntent(), 0);
            for (ResolveInfo resolveInfo : resolveInfoList) {
                webBrowserPackageNames.add(resolveInfo.activityInfo.packageName);
            }

            webBrowserPackageNames.add("com.google.android.webview");
            webBrowserPackageNames.add("com.android.htmlviewer");

            for (ApplicationInfo applicationInfo : pm.getInstalledApplications(0)) {
                // We need to always keep ourselves using the VPN, otherwise our
                // watchdog does not work.
                if (applicationInfo.packageName.equals(BuildConfig.APPLICATION_ID)) {
                    onVpn.add(applicationInfo.packageName);
                } else if (itemsOnVpn.contains(applicationInfo.packageName)) {
                    onVpn.add(applicationInfo.packageName);
                } else if (items.contains(applicationInfo.packageName)) {
                    notOnVpn.add(applicationInfo.packageName);
                } else if (defaultMode == DEFAULT_MODE_ON_VPN) {
                    onVpn.add(applicationInfo.packageName);
                } else if (defaultMode == DEFAULT_MODE_NOT_ON_VPN) {
                    notOnVpn.add(applicationInfo.packageName);
                } else if (defaultMode == DEFAULT_MODE_INTELLIGENT) {
                    if (webBrowserPackageNames.contains(applicationInfo.packageName))
                        onVpn.add(applicationInfo.packageName);
                    else if ((applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0)
                        notOnVpn.add(applicationInfo.packageName);
                    else
                        onVpn.add(applicationInfo.packageName);
                }
            }
        }

        /**
         * Returns an intent for opening a website, used for finding
         * web browsers. Extracted method for mocking.
         */
        Intent newBrowserIntent() {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setData(Uri.parse("https://isabrowser.dns66.jak-linux.org/"));
            return intent;
        }
    }
}
