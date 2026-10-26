package com.nexshare;

import android.content.Context;

import org.libtorrent4j.AddTorrentParams;
import org.libtorrent4j.SessionHandle;
import org.libtorrent4j.SessionManager;
import org.libtorrent4j.SessionParams;
import org.libtorrent4j.SettingsPack;
import org.libtorrent4j.Sha1Hash;
import org.libtorrent4j.TorrentFlags;
import org.libtorrent4j.TorrentHandle;
import org.libtorrent4j.TorrentInfo;
import org.libtorrent4j.TorrentStatus;
import org.libtorrent4j.swig.torrent_flags_t;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Collection;

/** BitTorrent downloads (magnet links and .torrent files) on top of libtorrent4j. */
public class TorrentEngine {

    private final SessionManager session;
    private final File torrentDir;

    public TorrentEngine(Context ctx) throws IOException {
        torrentDir = new File(ctx.getFilesDir(), "torrents");
        torrentDir.mkdirs();
        SettingsPack sp = new SettingsPack();
        sp.activeDownloads(4);
        sp.activeSeeds(4);
        sp.setEnableDht(true);
        session = new SessionManager(false);
        session.start(new SessionParams(sp));
        session.startDht();
    }

    void add(DownloadManager.Task t, String magnet, byte[] torrentFile, File dir) throws IOException {
        try {
            if (magnet != null) {
                AddTorrentParams p = AddTorrentParams.parseMagnetUri(magnet);
                t.hash = p.getInfoHashes().getBest().toHex();
                String dn = p.getName();
                if (dn != null && !dn.isEmpty()) t.name = dn;
                session.download(magnet, dir, new torrent_flags_t());
            } else {
                TorrentInfo ti = new TorrentInfo(torrentFile);
                t.hash = ti.infoHash().toHex();
                t.name = ti.name();
                t.total = ti.totalSize();
                write(new File(torrentDir, t.hash + ".torrent"), torrentFile);
                session.download(ti, dir);
            }
        } catch (IOException e) {
            throw e;
        } catch (Throwable e) {
            throw new IOException("invalid torrent: " + e.getMessage());
        }
    }

    /** Re-adds torrents saved in the task list after the app restarts. */
    void restore(Collection<DownloadManager.Task> tasks) {
        for (DownloadManager.Task t : tasks) {
            if (!t.type.equals("torrent") || t.hash == null) continue;
            try {
                File f = new File(torrentDir, t.hash + ".torrent");
                File dir = new File(t.dest);
                if (f.exists()) session.download(new TorrentInfo(f), dir);
                else if (t.url.startsWith("magnet:")) session.download(t.url, dir, new torrent_flags_t());
                if (t.status.equals("paused") || t.status.equals("done")) {
                    TorrentHandle h = handle(t);
                    if (h != null) pauseHandle(h);
                }
            } catch (Throwable e) {
                t.status = "error";
                t.error = e.getMessage();
            }
        }
    }

    void refresh(Collection<DownloadManager.Task> tasks) {
        for (DownloadManager.Task t : tasks) {
            if (!t.type.equals("torrent")) continue;
            TorrentHandle h = handle(t);
            if (h == null) {
                t.speed = t.upSpeed = 0;
                continue;
            }
            try {
                TorrentStatus s = h.status();
                if (s.hasMetadata()) {
                    t.name = s.name();
                    t.total = s.totalWanted();
                }
                t.done = s.totalWantedDone();
                t.speed = s.downloadPayloadRate();
                t.upSpeed = s.uploadPayloadRate();
                t.peers = s.numPeers();
                t.seeds = s.numSeeds();
                if (s.errorCode() != null && s.errorCode().isError()) {
                    t.status = "error";
                    t.error = s.errorCode().getMessage();
                    continue;
                }
                if (t.status.equals("paused") || t.status.equals("done")) {
                    t.speed = t.upSpeed = 0;
                    continue;
                }
                switch (s.state()) {
                    case DOWNLOADING_METADATA: t.status = "metadata"; break;
                    case CHECKING_FILES:
                    case CHECKING_RESUME_DATA: t.status = "checking"; break;
                    case FINISHED:
                    case SEEDING: t.status = "seeding"; break;
                    default: t.status = "downloading";
                }
            } catch (Throwable ignored) { }
        }
    }

    void pause(DownloadManager.Task t) {
        TorrentHandle h = handle(t);
        if (h != null) pauseHandle(h);
    }

    void resume(DownloadManager.Task t) throws IOException {
        TorrentHandle h = handle(t);
        if (h == null) throw new IOException("torrent not loaded");
        h.setFlags(TorrentFlags.AUTO_MANAGED);
        h.resume();
    }

    void remove(DownloadManager.Task t, boolean deleteFiles) {
        TorrentHandle h = handle(t);
        if (h != null) {
            if (deleteFiles) session.remove(h, SessionHandle.DELETE_FILES);
            else session.remove(h);
        }
        if (t.hash != null) new File(torrentDir, t.hash + ".torrent").delete();
    }

    void stop() {
        try {
            session.stop();
        } catch (Throwable ignored) { }
    }

    private void pauseHandle(TorrentHandle h) {
        h.unsetFlags(TorrentFlags.AUTO_MANAGED);
        h.pause();
    }

    private TorrentHandle handle(DownloadManager.Task t) {
        if (t.hash == null) return null;
        try {
            TorrentHandle h = session.find(Sha1Hash.parseHex(t.hash));
            return h != null && h.isValid() ? h : null;
        } catch (Throwable e) {
            return null;
        }
    }

    private static void write(File f, byte[] data) throws IOException {
        try (OutputStream out = new FileOutputStream(f)) {
            out.write(data);
        }
    }
}
