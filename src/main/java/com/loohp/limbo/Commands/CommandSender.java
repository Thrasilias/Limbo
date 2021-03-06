package com.loohp.limbo.Commands;

import java.util.UUID;

import net.md_5.bungee.api.chat.BaseComponent;

public interface CommandSender {
	
	public void sendMessage(BaseComponent[] component, UUID uuid);
	
	public void sendMessage(BaseComponent component, UUID uuid);
	
	public void sendMessage(String message, UUID uuid);
	
	public void sendMessage(BaseComponent[] component);
	
	public void sendMessage(BaseComponent component);
	
	public void sendMessage(String message);
	
	public boolean hasPermission(String permission);
	
	public String getName();

}
