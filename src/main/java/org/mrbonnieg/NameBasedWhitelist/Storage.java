package org.mrbonnieg.NameBasedWhitelist;

import java.util.List;

public interface Storage {
    List<String> getPlayers();
    boolean containsPlayer(String username);
    boolean addPlayer(String username);
    boolean removePlayer(String username);
    void saveWhitelist();
    boolean isReady();
}
