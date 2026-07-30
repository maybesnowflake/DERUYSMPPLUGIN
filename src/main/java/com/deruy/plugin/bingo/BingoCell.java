package com.deruy.plugin.bingo;

public class BingoCell {

    public enum Type { BIOME, ITEM }

    private final Type type;
    private final String value; // Biome enum name 또는 Material enum name

    public BingoCell(Type type, String value) {
        this.type = type;
        this.value = value;
    }

    public Type getType() {
        return type;
    }

    public String getValue() {
        return value;
    }

    public String display() {
        return (type == Type.BIOME ? "§a[바이옴] " : "§e[아이템] ") + value;
    }
}
