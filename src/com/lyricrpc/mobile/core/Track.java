package com.lyricrpc.mobile.core;

/** What is playing right now, however it was found. */
public final class Track {
    public String title = "";
    public String artist = "";
    public String album = "";
    public long durationMs;
    public long progressMs;
    public boolean playing;
    public String packageName = "";

    /** The song, ignoring where it is paused: two sightings of this key are the same track. */
    public String songId() {
        return Text.comparable(title) + "|" + Text.comparable(artist);
    }

    public boolean isSameSongAs(Track other) {
        if (other == null) return false;
        String mine = Text.comparable(title);
        String theirs = Text.comparable(other.title);
        if (mine.isEmpty() || theirs.isEmpty()) return false;
        if (!mine.equals(theirs)) return false;

        String left = Text.comparable(artist);
        String right = Text.comparable(other.artist);
        if (!left.isEmpty() && !right.isEmpty()
                && !left.equals(right)
                && !Text.firstArtist(artist).equals(Text.firstArtist(other.artist))) {
            return false;
        }

        // A remix or live take printed under the same name is a different song.
        if (durationMs > 0 && other.durationMs > 0
                && Math.abs(durationMs - other.durationMs) > 5000) {
            return false;
        }
        return true;
    }

    @Override
    public String toString() {
        return title + " - " + artist + " (" + progressMs + "/" + durationMs + "ms, playing=" + playing + ")";
    }
}
