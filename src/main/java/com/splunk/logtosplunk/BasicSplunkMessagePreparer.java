package com.splunk.logtosplunk;

import java.util.Properties;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.splunk.logtosplunk.event_loggers.PlayerEventLogger;
import com.splunk.logtosplunk.loggable_events.LoggableBlockEvent;
import com.splunk.logtosplunk.loggable_events.LoggableDeathEvent;
import com.splunk.logtosplunk.loggable_events.LoggableEvent;
import com.splunk.logtosplunk.loggable_events.LoggablePlayerEvent;

/**
 * Based off of the original Splunk Minecraft app, this message preparer takes data from Minecraft events and prepares
 * them for Splunk.
 */
public class BasicSplunkMessagePreparer implements SplunkMessagePreparer {
    static final String BASE_PLAYER_STRING = "action=%s player=%s";
    static final String ACTION = "action=%s";
    static final String REASON = " reason=%s";
    static final String MESSAGE = " message=\"%s\"";
    static final String BLOCK = " block_type=%s";
    static final String BASE_BLOCK_TYPE = " base_type=%s";
    static final String KILLER = " killer=%s";
    static final String VICTIM = " victim=%s";
    static final String DAMAGE_SOURCE = " damage_source=%s";

    /**
     * Connection(s) to Splunk.
     */
    private SplunkConnection connector;

    /**
     * Keeps track players last positions, in a guava cache for it's eviction policy.
     */
    private final Cache<String, Point3dLong> lastKnownCoordinates = CacheBuilder.newBuilder().maximumSize(
            PlayerEventLogger.MAX_PLAYERS).build(
            new CacheLoader<String, Point3dLong>() {
                @Override
                public Point3dLong load(String key) throws Exception {
                    return lastKnownCoordinates.getIfPresent(key);
                }
            });

    /**
     * Constructor, uses default Splunk connection which is initialized in the {@code init()} method.
     */
    public BasicSplunkMessagePreparer() {

    }

    /**
     * Constructor.
     *
     * @param splunkConnection Connection to splunk to use.
     */
    public BasicSplunkMessagePreparer(SplunkConnection splunkConnection) {
        this.connector = splunkConnection;
    }

    @Override
    public void writeMessage(LoggableEvent loggable) {
        if (loggable instanceof LoggablePlayerEvent) {
            writePlayerMessage((LoggablePlayerEvent) loggable);
        } else if (loggable instanceof LoggableBlockEvent) {
            writeBlockMessage((LoggableBlockEvent) loggable);
        } else if (loggable instanceof LoggableDeathEvent) {
            writeDeathMessage((LoggableDeathEvent) loggable);
        }
    }

    @Override
    public void writeMessage(String message) {
        connector.sendToSplunk(message);
    }

    @Override
    public void init(Properties props) {
        final String host = props.getProperty(LogToSplunkMod.SPLUNK_HOST_PROP_KEY,LogToSplunkMod.DEFAULT_HOST);
        final int port = Integer.valueOf(props.getProperty(LogToSplunkMod.SPLUNK_PORT_PROP_KEY, LogToSplunkMod.DEFAULT_PORT));
        connector = new SingleSplunkConnection(host,port,true);
    }

    /**
     * Processes a LoggableBlockEvent to send to Splunk.
     *
     * @param event The event to process.
     */
    private void writeBlockMessage(LoggableBlockEvent event) {
        StringBuilder b = new StringBuilder(
                String.format(BASE_PLAYER_STRING, event.getAction().asString(), event.getPlayerName()));
        b.append(' ' + extractLocation(event));
        b.append(String.format(BLOCK, event.getBlockName()));
        b.append(String.format(BASE_BLOCK_TYPE, event.getBaseType()));
        writeMessage(b.toString());
    }

    /**
     * Processes a LoggablePlayerEvent to send to Splunk.
     *
     * @param event The event to process.
     */
    private void writePlayerMessage(LoggablePlayerEvent event) {
        if (event.getAction() == LoggablePlayerEvent.PlayerEventAction.LOCATION) {
            logLegacyMoveEvent(event);
            return;
        }

        StringBuilder b = new StringBuilder(
                String.format(BASE_PLAYER_STRING, event.getAction().asString(), event.getPlayerName()));
        if (event.getReason() != null) {
            b.append(String.format(REASON, event.getReason()));
        }
        b.append(' ' + extractLocation(event));
        if (event.getMessage() != null) {
            b.append(String.format(MESSAGE, event.getMessage()));
        }
        writeMessage(b.toString());
    }

    /**
     * Log move data in the way of the original splunk minecraft app, which was based off of CraftBukkit's MoveEvent.
     *
     * @param event Movement event to be logged.
     */
    private void logLegacyMoveEvent(LoggablePlayerEvent event) {
        String playerName = event.getPlayerName();
        Point3dLong lastCoords = lastKnownCoordinates.getIfPresent(playerName);
        lastKnownCoordinates.put(playerName, event.getCoordinates());

        if (lastCoords == null) {
            return;
        }

        // message in this format to support old craftbukkit style move events (and how they were logged in splunk).
        writeMessage(
                "action=" + event.getAction().asString() +
                        " player=" + playerName +
                        " world=" + event.getWorldName() +
                        " from_x=" + lastCoords.xCoord +
                        " from_y=" + lastCoords.yCoord +
                        " from_z=" + lastCoords.zCoord +
                        " to_x=" + event.getCoordinates().xCoord +
                        " to_y=" + event.getCoordinates().yCoord +
                        " to_z=" + event.getCoordinates().zCoord +
                        " game_time=" + event.getWorldTime());
    }

    /**
     * Processes a {@link LoggableDeathEvent} to send to Splunk.
     *
     * @param event
     */
    private void writeDeathMessage(LoggableDeathEvent event) {
        StringBuilder b = new StringBuilder();
        b.append(String.format(ACTION, event.getAction().asString()));
        b.append(String.format(VICTIM, event.getVictim()));
        if (event.getKiller() != null) {
            b.append(String.format(KILLER, event.getKiller()));
        }
        b.append(String.format(DAMAGE_SOURCE, event.getDamageSource()));
        b.append(' ' + extractLocation(event));

        writeMessage(b.toString());
    }

    /**
     * Produce a String representing location in the original Splunk Minecraft App format.
     *
     * @param event The event to extract the location String from.
     * @return A String representing the location in the event.
     */
    private static String extractLocation(LoggableEvent event) {
        StringBuilder b = new StringBuilder();
        b.append("world=" + event.getWorldName());
        if (event.getCoordinates() != null) {
            b.append(
                    " " + "x=" + event.getCoordinates().xCoord +
                            " " + "y=" + event.getCoordinates().yCoord +
                            " " + "z=" + event.getCoordinates().zCoord);
        }
        b.append(" " + "game_time=" + event.getWorldTime());
        return b.toString();
    }
}