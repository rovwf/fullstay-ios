package com.fullstay.browser;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/** Sign-up and "stay in fullscreen?" checks against a throwaway local server that speaks the site's protocol. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 36)
public class RemoteFlagTest {
    private Context app;
    private String oldBase, oldKey;

    @Before public void setUp() {
        app = ApplicationProvider.getApplicationContext();
        Prefs.p(app).edit().clear().commit();
        oldBase = RemoteFlag.base;
        oldKey = RemoteFlag.key;
    }

    @After public void tearDown() {
        RemoteFlag.base = oldBase;
        RemoteFlag.key = oldKey;
    }

    @Test public void readsTheSitesReply() throws Exception {
        assertTrue(RemoteFlag.stayFrom("{\"ok\":true,\"stay\":true}"));
        assertFalse(RemoteFlag.stayFrom(" {\"ok\": true, \"stay\": false}\n"));
        for (String bad : new String[]{"", "true", "{}", "{\"ok\":false,\"gone\":true}", "{\"ok\":true}", "<html>"}) {
            try { RemoteFlag.stayFrom(bad); fail("should reject: " + bad); } catch (Exception expected) { }
        }
    }

    @Test public void infoDescribesThePhone() throws Exception {
        JSONObject o = RemoteFlag.info(app);
        assertFalse(o.getString("name").isEmpty());
        assertTrue(o.getString("os").startsWith("Android "));
        assertTrue(o.getString("screen").contains("\u00d7"));
        assertFalse(o.has("key"));
    }

    @Test public void noKeyMeansNoRemoteControl() {
        RemoteFlag.key = "";
        assertFalse(RemoteFlag.enabled());
        RemoteFlag.refresh(app);
        assertFalse(Prefs.p(app).contains(Prefs.REMOTE_EXIT));
    }

    @Test public void signsUpOnceThenReadsItsOwnSwitch() throws Exception {
        List<String> seen = new ArrayList<>();
        final boolean[] stay = {true};
        try (TinyServer s = new TinyServer((req) -> {
            seen.add(req.path);
            JSONObject b = req.json();
            if (req.path.equals("/_fs/register")) {
                if (!"right-key-right-key-right-key".equals(b.optString("key"))) return new String[]{"403", "{\"ok\":false}"};
                if (!"android".equals(b.optString("platform")) || b.optString("os").isEmpty()) return new String[]{"400", "{}"};
                return new String[]{"200", "{\"ok\":true,\"id\":\"dev1\",\"token\":\"tok1\",\"stay\":true}"};
            }
            if (req.path.equals("/_fs/state")) {
                if (!"dev1".equals(b.optString("id")) || !"tok1".equals(b.optString("token"))) return new String[]{"404", "{\"ok\":false,\"gone\":true}"};
                if (b.optJSONObject("info") == null) return new String[]{"400", "{}"};
                return new String[]{"200", "{\"ok\":true,\"stay\":" + stay[0] + "}"};
            }
            return new String[]{"404", "{}"};
        })) {
            RemoteFlag.base = s.base();
            RemoteFlag.key = "right-key-right-key-right-key";
            RemoteFlag.sync(app);
            assertEquals("dev1", Prefs.s(app, Prefs.FS_ID, null));
            assertEquals("tok1", Prefs.s(app, Prefs.FS_TOKEN, null));
            assertFalse("signed up switched on: stays in fullscreen", RemoteFlag.exitOnReturn(app));

            stay[0] = false;                                       // you flip the switch off on the site
            RemoteFlag.sync(app);
            assertTrue(RemoteFlag.exitOnReturn(app));
            stay[0] = true;
            RemoteFlag.sync(app);
            assertFalse(RemoteFlag.exitOnReturn(app));
            assertEquals("[/_fs/register, /_fs/state, /_fs/state]", seen.toString());
        }
    }

    @Test public void removedOnTheSiteSignsUpAgain() throws Exception {
        Prefs.p(app).edit().putString(Prefs.FS_ID, "old").putString(Prefs.FS_TOKEN, "oldtok").putBoolean(Prefs.REMOTE_EXIT, true).commit();
        try (TinyServer s = new TinyServer((req) -> req.path.equals("/_fs/state")
                ? new String[]{"404", "{\"ok\":false,\"gone\":true}"}
                : new String[]{"200", "{\"ok\":true,\"id\":\"new\",\"token\":\"newtok\",\"stay\":true}"})) {
            RemoteFlag.base = s.base();
            RemoteFlag.key = "right-key-right-key-right-key";
            RemoteFlag.sync(app);
            assertEquals("new", Prefs.s(app, Prefs.FS_ID, null));
            assertFalse(RemoteFlag.exitOnReturn(app));
        }
    }

    @Test public void wrongKeyOrServerTroubleKeepsTheLastValue() throws Exception {
        Prefs.p(app).edit().putBoolean(Prefs.REMOTE_EXIT, true).commit();
        try (TinyServer s = new TinyServer((req) -> new String[]{"403", "{\"ok\":false,\"error\":\"Wrong key.\"}"})) {
            RemoteFlag.base = s.base();
            RemoteFlag.key = "wrong-key-wrong-key-wrong-key";
            RemoteFlag.sync(app);
            assertNull(Prefs.s(app, Prefs.FS_ID, null));
            assertTrue(RemoteFlag.exitOnReturn(app));
        }
        Prefs.p(app).edit().putString(Prefs.FS_ID, "dev1").putString(Prefs.FS_TOKEN, "tok1").commit();
        try (TinyServer s = new TinyServer((req) -> new String[]{"500", "oops"})) {
            RemoteFlag.base = s.base();
            RemoteFlag.sync(app);
            assertTrue(RemoteFlag.exitOnReturn(app));
            assertEquals("a server error doesn't forget the phone", "dev1", Prefs.s(app, Prefs.FS_ID, null));
        }
        RemoteFlag.base = "http://127.0.0.1:1";                  // nothing listening
        RemoteFlag.sync(app);
        assertTrue(RemoteFlag.exitOnReturn(app));
    }

    // --- a one-request-at-a-time HTTP server that reads POST bodies ---

    static final class Req {
        final String path, body;
        Req(String path, String body) { this.path = path; this.body = body; }
        JSONObject json() { try { return new JSONObject(body); } catch (Exception e) { return new JSONObject(); } }
    }

    private static final class TinyServer implements AutoCloseable {
        final java.net.ServerSocket sock;
        final Function<Req, String[]> handler;
        volatile boolean stop;

        TinyServer(Function<Req, String[]> handler) throws Exception {
            this.handler = handler;
            sock = new java.net.ServerSocket(0, 0, java.net.InetAddress.getByName("127.0.0.1"));
            Thread t = new Thread(this::loop);
            t.setDaemon(true);
            t.start();
        }

        String base() { return "http://127.0.0.1:" + sock.getLocalPort(); }

        private void loop() {
            while (!stop) {
                try (java.net.Socket c = sock.accept()) {
                    java.io.InputStream in = c.getInputStream();
                    String line = readLine(in);
                    if (line == null) continue;
                    String[] parts = line.split(" ");
                    int len = 0;
                    while (true) {
                        String h = readLine(in);
                        if (h == null || h.isEmpty()) break;
                        if (h.toLowerCase(java.util.Locale.ROOT).startsWith("content-length:")) len = Integer.parseInt(h.substring(15).trim());
                    }
                    byte[] body = new byte[len];
                    int got = 0;
                    while (got < len) { int n = in.read(body, got, len - got); if (n < 0) break; got += n; }
                    String[] v = handler.apply(new Req(parts.length > 1 ? parts[1] : "/", new String(body, "UTF-8")));
                    byte[] out = (v.length > 1 ? v[1] : "").getBytes("UTF-8");
                    String head = "HTTP/1.1 " + v[0] + " X\r\nContent-Type: application/json\r\nContent-Length: " + out.length + "\r\nConnection: close\r\n\r\n";
                    c.getOutputStream().write(head.getBytes("UTF-8"));
                    c.getOutputStream().write(out);
                    c.getOutputStream().flush();
                } catch (Exception ignored) { }
            }
        }

        private static String readLine(java.io.InputStream in) throws java.io.IOException {
            StringBuilder sb = new StringBuilder();
            int ch;
            while ((ch = in.read()) >= 0) {
                if (ch == '\n') break;
                if (ch != '\r') sb.append((char) ch);
            }
            return ch < 0 && sb.length() == 0 ? null : sb.toString();
        }

        public void close() { stop = true; try { sock.close(); } catch (Exception ignored) { } }
    }
}
