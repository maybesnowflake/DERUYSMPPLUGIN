package com.deruy.plugin.events;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 모든 GameEvent 구현체를 이름으로 등록/조회하고, 시작·종료를 위임하는 레지스트리.
 * KOTH, 서플라이드랍, 바운티, 빙고, 건축대회 등은 각자 GameEvent를 구현한 뒤
 * 여기에 register() 로 등록만 하면 /devent 커맨드로 바로 조작 가능해진다.
 */
public class EventManager {

    private final Map<String, GameEvent> registeredEvents = new LinkedHashMap<>();

    public void register(GameEvent event) {
        registeredEvents.put(event.getName().toLowerCase(), event);
    }

    public Optional<GameEvent> get(String name) {
        return Optional.ofNullable(registeredEvents.get(name.toLowerCase()));
    }

    public Map<String, GameEvent> getAll() {
        return registeredEvents;
    }

    public boolean start(String name) {
        return get(name).map(e -> {
            e.start();
            return true;
        }).orElse(false);
    }

    public boolean stop(String name) {
        return get(name).map(e -> {
            e.stop();
            return true;
        }).orElse(false);
    }
}
