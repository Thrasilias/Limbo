package com.loohp.limbo.Server;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import com.loohp.limbo.DeclareCommands;
import com.loohp.limbo.Limbo;
import com.loohp.limbo.Events.PlayerJoinEvent;
import com.loohp.limbo.Events.PlayerLoginEvent;
import com.loohp.limbo.Events.PlayerMoveEvent;
import com.loohp.limbo.Events.PlayerQuitEvent;
import com.loohp.limbo.Events.StatusPingEvent;
import com.loohp.limbo.File.ServerProperties;
import com.loohp.limbo.Location.Location;
import com.loohp.limbo.Player.Player;
import com.loohp.limbo.Server.Packets.Packet;
import com.loohp.limbo.Server.Packets.PacketHandshakingIn;
import com.loohp.limbo.Server.Packets.PacketLoginInLoginStart;
import com.loohp.limbo.Server.Packets.PacketLoginOutDisconnect;
import com.loohp.limbo.Server.Packets.PacketLoginOutLoginSuccess;
import com.loohp.limbo.Server.Packets.PacketOut;
import com.loohp.limbo.Server.Packets.PacketPlayInChat;
import com.loohp.limbo.Server.Packets.PacketPlayInKeepAlive;
import com.loohp.limbo.Server.Packets.PacketPlayInPosition;
import com.loohp.limbo.Server.Packets.PacketPlayInPositionAndLook;
import com.loohp.limbo.Server.Packets.PacketPlayInRotation;
import com.loohp.limbo.Server.Packets.PacketPlayInTabComplete;
import com.loohp.limbo.Server.Packets.PacketPlayOutDeclareCommands;
import com.loohp.limbo.Server.Packets.PacketPlayOutDisconnect;
import com.loohp.limbo.Server.Packets.PacketPlayOutLightUpdate;
import com.loohp.limbo.Server.Packets.PacketPlayOutLogin;
import com.loohp.limbo.Server.Packets.PacketPlayOutMapChunk;
import com.loohp.limbo.Server.Packets.PacketPlayOutPlayerAbilities;
import com.loohp.limbo.Server.Packets.PacketPlayOutPlayerAbilities.PlayerAbilityFlags;
import com.loohp.limbo.Server.Packets.PacketPlayOutPlayerInfo;
import com.loohp.limbo.Server.Packets.PacketPlayOutPlayerInfo.PlayerInfoAction;
import com.loohp.limbo.Server.Packets.PacketPlayOutPlayerInfo.PlayerInfoData;
import com.loohp.limbo.Server.Packets.PacketPlayOutPlayerInfo.PlayerInfoData.PlayerInfoDataAddPlayer.PlayerSkinProperty;
import com.loohp.limbo.Server.Packets.PacketPlayOutPositionAndLook;
import com.loohp.limbo.Server.Packets.PacketPlayOutShowPlayerSkins;
import com.loohp.limbo.Server.Packets.PacketPlayOutSpawnPosition;
import com.loohp.limbo.Server.Packets.PacketPlayOutTabComplete;
import com.loohp.limbo.Server.Packets.PacketPlayOutTabComplete.TabCompleteMatches;
import com.loohp.limbo.Server.Packets.PacketPlayOutUpdateViewPosition;
import com.loohp.limbo.Server.Packets.PacketStatusInPing;
import com.loohp.limbo.Server.Packets.PacketStatusInRequest;
import com.loohp.limbo.Server.Packets.PacketStatusOutPong;
import com.loohp.limbo.Server.Packets.PacketStatusOutResponse;
import com.loohp.limbo.Utils.CustomStringUtils;
import com.loohp.limbo.Utils.DataTypeIO;
import com.loohp.limbo.Utils.GameMode;
import com.loohp.limbo.Utils.MojangAPIUtils;
import com.loohp.limbo.Utils.MojangAPIUtils.SkinResponse;
import com.loohp.limbo.Utils.NamespacedKey;
import com.loohp.limbo.World.BlockPosition;
import com.loohp.limbo.World.World;

import net.md_5.bungee.api.ChatColor;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.chat.ComponentSerializer;
import net.querz.mca.Chunk;

public class ClientConnection extends Thread {
	
	public enum ClientState {
		LEGACY,
		HANDSHAKE,
		STATUS,
		LOGIN,
		PLAY,
		DISCONNECTED;
	}

    private final Socket client_socket;
    private boolean running;
    private ClientState state;
    
    private Player player;	
	private AtomicLong lastKeepAlivePayLoad;
	
	private DataOutputStream output;
	private DataInputStream input;
	
	private InetAddress inetAddress;
	
    public ClientConnection(Socket client_socket) {
        this.client_socket = client_socket;
        this.inetAddress = client_socket.getInetAddress();
        this.lastKeepAlivePayLoad = new AtomicLong();
    }
	
	public InetAddress getInetAddress() {
		return inetAddress;
	}

	public long getLastKeepAlivePayLoad() {
		return lastKeepAlivePayLoad.get();
	}
	
	public void setLastKeepAlivePayLoad(long payLoad) {
		this.lastKeepAlivePayLoad.set(payLoad);
	}

	public Player getPlayer() {
		return player;
	}
    
    public ClientState getClientState() {
    	return state;
    }
    
    public Socket getSocket() {
    	return client_socket;
    }
    
	public boolean isRunning() {
		return running;
	}
	
	public synchronized void sendPacket(PacketOut packet) throws IOException {
		byte[] packetByte = packet.serializePacket();
		DataTypeIO.writeVarInt(output, packetByte.length);
		output.write(packetByte);
		output.flush();
	}
	
	public void disconnect(BaseComponent[] reason) {
		try {
			PacketPlayOutDisconnect packet = new PacketPlayOutDisconnect(ComponentSerializer.toString(reason));
			sendPacket(packet);
		} catch (IOException e) {}
		try {
			client_socket.close();
		} catch (IOException e) {}
	}
	
	public void disconnectDuringLogin(BaseComponent[] reason) {
		try {
			PacketLoginOutDisconnect packet = new PacketLoginOutDisconnect(ComponentSerializer.toString(reason));
			sendPacket(packet);
		} catch (IOException e) {}
		try {
			client_socket.close();
		} catch (IOException e) {}
	}

	@SuppressWarnings("deprecation")
	@Override
    public void run() {
        running = true;
        state = ClientState.HANDSHAKE;
    	try {
    		client_socket.setKeepAlive(true);
    		input = new DataInputStream(client_socket.getInputStream());
    		output = new DataOutputStream(client_socket.getOutputStream());
		    int handShakeSize = DataTypeIO.readVarInt(input);

		    //legacy ping
		    if (handShakeSize == 0xFE) {
		    	state = ClientState.LEGACY;			    
		    	output.writeByte(255);
		    	String str = client_socket.getInetAddress().getHostName() + ":" + client_socket.getPort();
		    	Limbo.getInstance().getConsole().sendMessage("[/" + str + "] <-> Legacy Status has pinged");
		    	ServerProperties p = Limbo.getInstance().getServerProperties();
		    	StatusPingEvent event = Limbo.getInstance().getEventsManager().callEvent(new StatusPingEvent(this, p.getVersionString(), p.getProtocol(), ComponentSerializer.parse(p.getMotdJson()), p.getMaxPlayers(), Limbo.getInstance().getPlayers().size(), p.getFavicon().orElse(null)));
				String response = Limbo.getInstance().buildLegacyPingResponse(event.getVersion(), event.getMotd(), event.getMaxPlayers(), event.getPlayersOnline());
				byte[] bytes = response.getBytes(StandardCharsets.UTF_16BE);
				output.writeShort(response.length());
				output.write(bytes);
				
				client_socket.close();
				state = ClientState.DISCONNECTED;
		    }
		    
		    @SuppressWarnings("unused")
			int handShakeId = DataTypeIO.readVarInt(input);
		    
		    PacketHandshakingIn handshake = new PacketHandshakingIn(input);
		    
		    boolean isBungeecord = Limbo.getInstance().getServerProperties().isBungeecord();		    
		    String bungeeForwarding = handshake.getServerAddress();
		    UUID bungeeUUID = null;
		    SkinResponse bungeeSkin = null;
		    
		    switch (handshake.getHandshakeType()) {
		    case STATUS:
				state = ClientState.STATUS;
				while (client_socket.isConnected()) {
					DataTypeIO.readVarInt(input);
					int packetId = DataTypeIO.readVarInt(input);
					Class<? extends Packet> packetType = Packet.getStatusIn().get(packetId);
					if (packetType == null) {
						//do nothing
					} else if (packetType.equals(PacketStatusInRequest.class)) {
						String str = client_socket.getInetAddress().getHostName() + ":" + client_socket.getPort();
						Limbo.getInstance().getConsole().sendMessage("[/" + str + "] <-> Handshake Status has pinged");
						ServerProperties p = Limbo.getInstance().getServerProperties();		
						StatusPingEvent event = Limbo.getInstance().getEventsManager().callEvent(new StatusPingEvent(this, p.getVersionString(), p.getProtocol(), ComponentSerializer.parse(p.getMotdJson()), p.getMaxPlayers(), Limbo.getInstance().getPlayers().size(), p.getFavicon().orElse(null)));												
						PacketStatusOutResponse packet = new PacketStatusOutResponse(Limbo.getInstance().buildServerListResponseJson(event.getVersion(), event.getProtocol(), event.getMotd(), event.getMaxPlayers(), event.getPlayersOnline(), event.getFavicon()));
						sendPacket(packet);
					} else if (packetType.equals(PacketStatusInPing.class)) {
						PacketStatusInPing ping = new PacketStatusInPing(input);
						PacketStatusOutPong packet = new PacketStatusOutPong(ping.getPayload());
						sendPacket(packet);
						break;
					}
				}
				break;
			case LOGIN:
				state = ClientState.LOGIN;
				
				if (isBungeecord) {
					try {
				    	String[] data = bungeeForwarding.split("\\x00");
				    	//String host = data[0];
				    	String ip = data[1];
				    	
				    	bungeeUUID = UUID.fromString(data[2].replaceFirst("([0-9a-fA-F]{8})([0-9a-fA-F]{4})([0-9a-fA-F]{4})([0-9a-fA-F]{4})([0-9a-fA-F]+)", "$1-$2-$3-$4-$5"));
				    	inetAddress = InetAddress.getByName(ip);
				    	
				    	String skinJson = data[3];
				    	
				    	String skin = skinJson.split("\"value\":\"")[1].split("\"")[0];
			            String signature = skinJson.split("\"signature\":\"")[1].split("\"")[0];
				    	bungeeSkin = new SkinResponse(skin, signature);
					} catch (Exception e) {
						Limbo.getInstance().getConsole().sendMessage("If you wish to use bungeecord's IP forwarding, please enable that in your bungeecord config.yml as well!");
						disconnectDuringLogin(new BaseComponent[] {new TextComponent(ChatColor.RED + "Please connect from the proxy!")});
					}
			    }
				
				while (client_socket.isConnected()) {
					int size = DataTypeIO.readVarInt(input);
					int packetId = DataTypeIO.readVarInt(input);
					Class<? extends Packet> packetType = Packet.getLoginIn().get(packetId);
					
					if (packetType == null) {
						input.skipBytes(size - DataTypeIO.getVarIntLength(packetId));
					} else if (packetType.equals(PacketLoginInLoginStart.class)) {
						PacketLoginInLoginStart start = new PacketLoginInLoginStart(input);
						String username = start.getUsername();
						UUID uuid = isBungeecord ? bungeeUUID : UUID.nameUUIDFromBytes(("OfflinePlayer:" + username).getBytes(StandardCharsets.UTF_8));
						
						PacketLoginOutLoginSuccess success = new PacketLoginOutLoginSuccess(uuid, username);
						sendPacket(success);
						
						state = ClientState.PLAY;

						player = new Player(this, username, uuid, Limbo.getInstance().getNextEntityId(), Limbo.getInstance().getServerProperties().getWorldSpawn());
						Limbo.getInstance().addPlayer(player);
						
						break;
					} else {
						input.skipBytes(size - DataTypeIO.getVarIntLength(packetId));
					}
	    		}
				
				PlayerLoginEvent event = Limbo.getInstance().getEventsManager().callEvent(new PlayerLoginEvent(this, false));
				if (event.isCancelled()) {
					disconnectDuringLogin(event.getCancelReason());
				}
				
				break;
		    }
		    
		    if (state == ClientState.PLAY) {
		    	
		    	TimeUnit.MILLISECONDS.sleep(500);
		    	
		    	ServerProperties p = Limbo.getInstance().getServerProperties();
    			PacketPlayOutLogin join = new PacketPlayOutLogin(player.getEntityId(), false, p.getDefaultGamemode(), Limbo.getInstance().getWorlds().stream().map(each -> new NamespacedKey(each.getName()).toString()).collect(Collectors.toList()).toArray(new String[Limbo.getInstance().getWorlds().size()]), Limbo.getInstance().getDimensionRegistry().getCodec(), p.getWorldSpawn().getWorld(), 0, (byte) p.getMaxPlayers(), 8, p.isReducedDebugInfo(), true, false, true);
    			sendPacket(join);
    			Limbo.getInstance().getUnsafe().setPlayerGameModeSilently(player, p.getDefaultGamemode());
				
				Location s = p.getWorldSpawn();
				
				//PacketPlayOutKeepAlive alive = new PacketPlayOutKeepAlive((long) (Math.random() * Long.MAX_VALUE));
				
				World world = s.getWorld();
				
				for (int x = 0; x < world.getChunks().length; x++) {
					for (int z = 0; z < world.getChunks()[x].length; z++) {
						Chunk chunk = world.getChunks()[x][z];
						if (chunk != null) {
							PacketPlayOutMapChunk chunkdata = new PacketPlayOutMapChunk(x, z, chunk, world.getEnvironment());
							sendPacket(chunkdata);
						}
					}
				}

				for (int x = 0; x < world.getChunks().length; x++) {
					for (int z = 0; z < world.getChunks()[x].length; z++) {
						List<Byte[]> blockChunk = world.getLightEngineBlock().getBlockLightBitMask(x, z);
						if (blockChunk == null) {
							blockChunk = new ArrayList<>();
						}
						List<Byte[]> skyChunk = null;
						if (world.hasSkyLight()) {
							skyChunk = world.getLightEngineSky().getSkyLightBitMask(x, z);
						}
						if (skyChunk == null) {
							skyChunk = new ArrayList<>();
						}
						PacketPlayOutLightUpdate chunkdata = new PacketPlayOutLightUpdate(x, z, true, skyChunk, blockChunk);
						sendPacket(chunkdata);
					}
				}
				
				SkinResponse skinresponce = isBungeecord ? bungeeSkin : MojangAPIUtils.getSkinFromMojangServer(player.getName());
				PlayerSkinProperty skin = skinresponce != null ? new PlayerSkinProperty(skinresponce.getSkin(), skinresponce.getSignature()) : null;
				PacketPlayOutPlayerInfo info = new PacketPlayOutPlayerInfo(PlayerInfoAction.ADD_PLAYER, player.getUUID(), new PlayerInfoData.PlayerInfoDataAddPlayer(player.getName(), Optional.ofNullable(skin), p.getDefaultGamemode(), 0, false, Optional.empty()));
				sendPacket(info);
				/*
				for (ClientConnection client : Limbo.getInstance().getServerConnection().getClients()) {
					DataOutputStream other = new DataOutputStream(client.getSocket().getOutputStream());
					DataTypeIO.writeVarInt(other, packetByte.length);
					other.write(packetByte);
				}
				*/
				
				PacketPlayOutShowPlayerSkins show = new PacketPlayOutShowPlayerSkins(player.getEntityId());
				sendPacket(show);
				
				Set<PlayerAbilityFlags> flags = new HashSet<>();
				if (p.isAllowFlight()) {
					flags.add(PlayerAbilityFlags.FLY);
				}
				if (player.getGamemode().equals(GameMode.CREATIVE)) {
					flags.add(PlayerAbilityFlags.CREATIVE);
				}
				PacketPlayOutPlayerAbilities abilities = new PacketPlayOutPlayerAbilities(0.05F, 0.1F, flags.toArray(new PlayerAbilityFlags[flags.size()]));
				sendPacket(abilities);
				
				String str = client_socket.getInetAddress().getHostName() + ":" + client_socket.getPort() + "|" + player.getName();
				Limbo.getInstance().getConsole().sendMessage("[/" + str + "] <-> Player had connected to the Limbo server!");
				
				PacketPlayOutDeclareCommands declare = DeclareCommands.getDeclareCommandsPacket(player);
				if (declare != null) {
					sendPacket(declare);
				}
				
				Limbo.getInstance().getEventsManager().callEvent(new PlayerJoinEvent(player));
				
				PacketPlayOutSpawnPosition spawnPos = new PacketPlayOutSpawnPosition(BlockPosition.from(s));
				sendPacket(spawnPos);
				
				PacketPlayOutPositionAndLook positionLook = new PacketPlayOutPositionAndLook(s.getX(), s.getY(), s.getZ(), s.getYaw(), s.getPitch(), 1);
				player.setLocation(new Location(world, s.getX(), s.getY(), s.getZ(), s.getYaw(), s.getPitch()));
				sendPacket(positionLook);
				
				while (client_socket.isConnected()) {
					try {					
						int size = DataTypeIO.readVarInt(input);
						int packetId = DataTypeIO.readVarInt(input);
						Class<? extends Packet> packetType = Packet.getPlayIn().get(packetId);
						//Limbo.getInstance().getConsole().sendMessage(packetId + " -> " + packetType);
						if (packetType == null) {
							input.skipBytes(size - DataTypeIO.getVarIntLength(packetId));
						} else if (packetType.equals(PacketPlayInPositionAndLook.class)) {					
							PacketPlayInPositionAndLook pos = new PacketPlayInPositionAndLook(input);
							Location from = player.getLocation();
							Location to = new Location(player.getWorld(), pos.getX(), pos.getY(), pos.getZ(), pos.getYaw(), pos.getPitch());
							
							PlayerMoveEvent event = Limbo.getInstance().getEventsManager().callEvent(new PlayerMoveEvent(player, from, to));
							if (event.isCancelled()) {
								Location returnTo = event.getFrom();
								PacketPlayOutPositionAndLook cancel = new PacketPlayOutPositionAndLook(returnTo.getX(), returnTo.getY(), returnTo.getZ(), returnTo.getYaw(), returnTo.getPitch(), 1);
								sendPacket(cancel);
							} else {
								player.setLocation(event.getTo());							
								PacketPlayOutUpdateViewPosition response = new PacketPlayOutUpdateViewPosition((int) player.getLocation().getX() >> 4, (int) player.getLocation().getZ() >> 4);
								sendPacket(response);
							}
						} else if (packetType.equals(PacketPlayInPosition.class)) {
							PacketPlayInPosition pos = new PacketPlayInPosition(input);
							Location from = player.getLocation();
							Location to = new Location(player.getWorld(), pos.getX(), pos.getY(), pos.getZ(), player.getLocation().getYaw(), player.getLocation().getPitch());
							
							PlayerMoveEvent event = Limbo.getInstance().getEventsManager().callEvent(new PlayerMoveEvent(player, from, to));
							if (event.isCancelled()) {
								Location returnTo = event.getFrom();
								PacketPlayOutPositionAndLook cancel = new PacketPlayOutPositionAndLook(returnTo.getX(), returnTo.getY(), returnTo.getZ(), returnTo.getYaw(), returnTo.getPitch(), 1);
								sendPacket(cancel);
							} else {
								player.setLocation(event.getTo());							
								PacketPlayOutUpdateViewPosition response = new PacketPlayOutUpdateViewPosition((int) player.getLocation().getX() >> 4, (int) player.getLocation().getZ() >> 4);
								sendPacket(response);
							}
						} else if (packetType.equals(PacketPlayInRotation.class)) {
							PacketPlayInRotation pos = new PacketPlayInRotation(input);
							Location from = player.getLocation();
							Location to = new Location(player.getWorld(), player.getLocation().getX(), player.getLocation().getY(), player.getLocation().getZ(), pos.getYaw(), pos.getPitch());
							
							PlayerMoveEvent event = Limbo.getInstance().getEventsManager().callEvent(new PlayerMoveEvent(player, from, to));
							if (event.isCancelled()) {
								Location returnTo = event.getFrom();
								PacketPlayOutPositionAndLook cancel = new PacketPlayOutPositionAndLook(returnTo.getX(), returnTo.getY(), returnTo.getZ(), returnTo.getYaw(), returnTo.getPitch(), 1);
								sendPacket(cancel);
							} else {
								player.setLocation(event.getTo());
								PacketPlayOutUpdateViewPosition response = new PacketPlayOutUpdateViewPosition((int) player.getLocation().getX() >> 4, (int) player.getLocation().getZ() >> 4);
								sendPacket(response);
							}
						} else if (packetType.equals(PacketPlayInKeepAlive.class)) {
							PacketPlayInKeepAlive alive = new PacketPlayInKeepAlive(input);
							if (alive.getPayload() != getLastKeepAlivePayLoad()) {
								Limbo.getInstance().getConsole().sendMessage("Incorrect Payload recieved in KeepAlive packet for player " + player.getName());
								break;
							}
						} else if (packetType.equals(PacketPlayInTabComplete.class)) {
							PacketPlayInTabComplete request = new PacketPlayInTabComplete(input);
							String[] command = CustomStringUtils.splitStringToArgs(request.getText().substring(1));

							List<TabCompleteMatches> matches = new ArrayList<TabCompleteMatches>();
							
							matches.addAll(Limbo.getInstance().getPluginManager().getTabOptions(player, command).stream().map(each -> new TabCompleteMatches(each)).collect(Collectors.toList()));
							
							int start = CustomStringUtils.getIndexOfArg(request.getText(), command.length - 1) + 1;
							int length = command[command.length - 1].length();
							
							PacketPlayOutTabComplete response = new PacketPlayOutTabComplete(request.getId(), start, length, matches.toArray(new TabCompleteMatches[matches.size()]));
							sendPacket(response);
						} else if (packetType.equals(PacketPlayInChat.class)) {
							PacketPlayInChat chat = new PacketPlayInChat(input);
							if (chat.getMessage().startsWith("/")) {
								Limbo.getInstance().dispatchCommand(player, chat.getMessage());
							} else {
								player.chat(chat.getMessage());
							}
						} else {
							input.skipBytes(size - DataTypeIO.getVarIntLength(packetId));
						}
						
					} catch (Exception e) {
						break;
					}
	    		}
				
				Limbo.getInstance().getEventsManager().callEvent(new PlayerQuitEvent(player));
		    	
		    	str = client_socket.getInetAddress().getHostName() + ":" + client_socket.getPort() + "|" + player.getName();
		    	Limbo.getInstance().getConsole().sendMessage("[/" + str + "] <-> Player had disconnected!");				
				
	    	}
		    
    	} catch (Exception e) {}
    	
    	try {
			client_socket.close();
		} catch (IOException e) {}
    	state = ClientState.DISCONNECTED;
    	
    	if (player != null) {
    		Limbo.getInstance().removePlayer(player);
    	}
    	Limbo.getInstance().getServerConnection().getClients().remove(this);
		running = false;
	}
}
