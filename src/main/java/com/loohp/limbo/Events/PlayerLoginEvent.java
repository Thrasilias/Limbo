package com.loohp.limbo.Events;

import com.loohp.limbo.Server.ClientConnection;

import net.md_5.bungee.api.chat.BaseComponent;

public class PlayerLoginEvent extends Event implements Cancellable {
	
	private ClientConnection connection;
	private boolean cancelled;
	private BaseComponent[] cancelReason;
	
	public PlayerLoginEvent(ClientConnection connection, boolean cancelled, BaseComponent... cancelReason) {
		this.connection = connection;
		this.cancelled = cancelled;
		this.cancelReason = cancelReason;
	}

	public ClientConnection getConnection() {
		return connection;
	}		

	public BaseComponent[] getCancelReason() {
		return cancelReason;
	}

	public void setCancelReason(BaseComponent... cancelReason) {
		this.cancelReason = cancelReason;
	}

	@Override
	public void setCancelled(boolean cancelled) {
		this.cancelled = cancelled;
	}

	@Override
	public boolean isCancelled() {
		return cancelled;
	}

}
