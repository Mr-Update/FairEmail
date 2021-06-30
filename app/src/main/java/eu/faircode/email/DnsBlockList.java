package eu.faircode.email;

/*
    This file is part of FairEmail.

    FairEmail is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    FairEmail is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with FairEmail.  If not, see <http://www.gnu.org/licenses/>.

    Copyright 2018-2021 by Marcel Bokhorst (M66B)
*/

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import androidx.preference.PreferenceManager;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.Hashtable;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.mail.internet.MimeUtility;

public class DnsBlockList {
    static final List<BlockList> BLOCKLISTS = Collections.unmodifiableList(Arrays.asList(
            // https://www.spamhaus.org/zen/
            new BlockList(true, "Spamhaus/zen", "zen.spamhaus.org", true, new String[]{
                    // https://www.spamhaus.org/faq/section/DNSBL%20Usage#200
                    "127.0.0.2", // SBL Spamhaus SBL Data
                    "127.0.0.3", // SBL Spamhaus SBL CSS Data
                    "127.0.0.4", // XBL CBL Data
                    "127.0.0.9", // SBL Spamhaus DROP/EDROP Data
                    //127.0.0.10 PBL ISP Maintained
                    //127.0.0.11 PBL Spamhaus Maintained
            }),

            // https://www.spamhaus.org/dbl/
            new BlockList(false, "Spamhaus/DBL", "dbl.spamhaus.org", false, new String[]{
                    // https://www.spamhaus.org/faq/section/Spamhaus%20DBL#291
                    "127.0.1.2", // spam domain
                    "127.0.1.4", // phish domain
                    "127.0.1.5", // malware domain
                    "127.0.1.6", // botnet C&C domain
                    "127.0.1.102", // abused legit spam
                    "127.0.1.103", // abused spammed redirector domain
                    "127.0.1.104", // abused legit phish
                    "127.0.1.105", // abused legit malware
                    "127.0.1.106", // abused legit botnet C&C
            }),

            new BlockList(true, "Spamcop", "bl.spamcop.net", true, new String[]{
                    // https://www.spamcop.net/fom-serve/cache/291.html
                    "127.0.0.2",
            }),

            new BlockList(false, "Barracuda", "b.barracudacentral.org", true, new String[]{
                    // https://www.barracudacentral.org/rbl/how-to-use
                    "127.0.0.2",
            })
    ));

    private static final long CACHE_EXPIRY_AFTER = 3600 * 1000L; // milliseconds
    private static final Map<String, CacheEntry> cache = new Hashtable<>();

    static void setEnabled(Context context, BlockList blocklist, boolean enabled) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        if (blocklist.enabled == enabled)
            prefs.edit().remove("blocklist." + blocklist.name).apply();
        else
            prefs.edit().putBoolean("blocklist." + blocklist.name, enabled).apply();

        synchronized (cache) {
            cache.clear();
        }
    }

    static boolean isEnabled(Context context, BlockList blocklist) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        return prefs.getBoolean("blocklist." + blocklist.name, blocklist.enabled);
    }

    static void reset(Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        SharedPreferences.Editor editor = prefs.edit();
        for (BlockList blocklist : BLOCKLISTS)
            editor.remove("blocklist." + blocklist.name);
        editor.apply();

        synchronized (cache) {
            cache.clear();
        }
    }

    static List<String> getNames(Context context) {
        List<String> names = new ArrayList<>();
        for (BlockList blocklist : BLOCKLISTS)
            if (isEnabled(context, blocklist))
                names.add(blocklist.name);
        return names;
    }

    static Boolean isJunk(Context context, String[] received) {
        if (received == null || received.length == 0)
            return null;

        String h = MimeUtility.unfold(received[received.length - 1]);
        String[] words = h.split("\\s+");
        for (int i = 0; i < words.length - 1; i++)
            if ("from".equalsIgnoreCase(words[i])) {
                String host = words[i + 1].toLowerCase(Locale.ROOT);
                if (!TextUtils.isEmpty(host))
                    return isJunk(context, host, BLOCKLISTS);
            }

        return null;
    }

    private static boolean isJunk(Context context, String host, List<BlockList> blocklists) {
        synchronized (cache) {
            CacheEntry entry = cache.get(host);
            if (entry != null && !entry.isExpired())
                return entry.isJunk();
        }

        boolean blocked = false;
        for (BlockList blocklist : blocklists)
            if (isEnabled(context, blocklist) && isJunk(host, blocklist)) {
                blocked = true;
                break;
            }

        synchronized (cache) {
            cache.put(host, new CacheEntry(blocked));
        }

        return blocked;
    }

    private static boolean isJunk(String host, BlockList blocklist) {
        boolean numeric = host.startsWith("[") && host.endsWith("]");
        if (numeric)
            host = host.substring(1, host.length() - 1);
        try {
            if (blocklist.numeric) {
                long start = new Date().getTime();
                InetAddress[] addresses = InetAddress.getAllByName(host);
                long elapsed = new Date().getTime() - start;
                Log.i("isJunk resolved=" + host + " elapse=" + elapsed + " ms");
                for (InetAddress addr : addresses) {
                    if (addr.isLoopbackAddress() ||
                            addr.isLinkLocalAddress() ||
                            addr.isSiteLocalAddress() ||
                            addr.isMulticastAddress()) {
                        Log.i("isJunk local=" + addr);
                        continue;
                    }
                    try {
                        StringBuilder lookup = new StringBuilder();
                        if (addr instanceof Inet4Address) {
                            byte[] a = addr.getAddress();
                            for (int i = 3; i >= 0; i--)
                                lookup.append(a[i] & 0xff).append('.');
                        } else if (addr instanceof Inet6Address) {
                            byte[] a = addr.getAddress();
                            for (int i = 15; i >= 0; i--) {
                                int b = a[i] & 0xff;
                                lookup.append(String.format("%01x", b & 0xf)).append('.');
                                lookup.append(String.format("%01x", b >> 4)).append('.');
                            }
                        }

                        lookup.append(blocklist.address);

                        if (isJunk(lookup.toString(), blocklist.responses))
                            return true;
                    } catch (Throwable ex) {
                        Log.w(ex);
                    }
                }
            } else if (!numeric) {
                long start = new Date().getTime();
                String lookup = host + "." + blocklist.address;
                boolean junk = isJunk(lookup, blocklist.responses);
                long elapsed = new Date().getTime() - start;
                Log.i("isJunk" + " " + lookup + "=" + junk + " elapsed=" + elapsed);
                return junk;
            }
        } catch (Throwable ex) {
            Log.w(ex);
        }

        return false;
    }

    private static boolean isJunk(String lookup, InetAddress[] responses) {
        long start = new Date().getTime();
        InetAddress result;
        try {
            // Possibly blocked
            result = InetAddress.getByName(lookup);
        } catch (UnknownHostException ignored) {
            // Not blocked
            result = null;
        }
        long elapsed = new Date().getTime() - start;

        boolean blocked = false;
        if (result != null && responses.length > 0) {
            for (InetAddress response : responses)
                if (response.equals(result)) {
                    blocked = true;
                    break;
                }
            if (!blocked)
                result = null;
        }

        Log.w("isJunk" +
                " lookup=" + lookup +
                " result=" + (result == null ? null : result.getHostAddress()) +
                " blocked=" + blocked +
                " elapsed=" + elapsed);

        return blocked;
    }

    private static class CacheEntry {
        private final long time;
        private final boolean blocked;

        CacheEntry(boolean blocked) {
            this.time = new Date().getTime();
            this.blocked = blocked;
        }

        boolean isExpired() {
            return (new Date().getTime() - this.time) > CACHE_EXPIRY_AFTER;
        }

        boolean isJunk() {
            return blocked;
        }
    }

    static class BlockList {
        int id;
        boolean enabled;
        String name;
        String address;
        boolean numeric;
        InetAddress[] responses;

        private static int nextid = 1;

        BlockList(boolean enabled, String name, String address, boolean numeric, String[] responses) {
            this.id = nextid++;
            this.enabled = enabled;
            this.name = name;
            this.address = address;
            this.numeric = numeric;
            List<InetAddress> r = new ArrayList<>();
            for (String response : responses)
                try {
                    r.add(InetAddress.getByName(response));
                } catch (UnknownHostException ex) {
                    Log.e(ex);
                }
            this.responses = r.toArray(new InetAddress[0]);
        }
    }
}
