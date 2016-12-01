/*
 * TeleStax, Open Source Cloud Communications
 * Copyright 2011-2016, Telestax Inc and individual contributors
 * by the @authors tag. 
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */

package org.mobicents.media.control.mgcp.endpoint;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.log4j.Logger;
import org.mobicents.media.control.mgcp.command.NotificationRequest;
import org.mobicents.media.control.mgcp.command.param.NotifiedEntity;
import org.mobicents.media.control.mgcp.connection.MgcpCall;
import org.mobicents.media.control.mgcp.connection.MgcpConnection;
import org.mobicents.media.control.mgcp.connection.MgcpConnectionProvider;
import org.mobicents.media.control.mgcp.connection.MgcpRemoteConnection;
import org.mobicents.media.control.mgcp.exception.MgcpCallNotFoundException;
import org.mobicents.media.control.mgcp.exception.MgcpConnectionException;
import org.mobicents.media.control.mgcp.exception.MgcpConnectionNotFound;
import org.mobicents.media.control.mgcp.exception.MgcpException;
import org.mobicents.media.control.mgcp.exception.MgcpSignalException;
import org.mobicents.media.control.mgcp.listener.MgcpCallListener;
import org.mobicents.media.control.mgcp.listener.MgcpConnectionListener;
import org.mobicents.media.control.mgcp.message.MessageDirection;
import org.mobicents.media.control.mgcp.message.MgcpMessage;
import org.mobicents.media.control.mgcp.message.MgcpMessageObserver;
import org.mobicents.media.control.mgcp.message.MgcpParameterType;
import org.mobicents.media.control.mgcp.message.MgcpRequest;
import org.mobicents.media.control.mgcp.message.MgcpRequestType;
import org.mobicents.media.control.mgcp.pkg.MgcpEvent;
import org.mobicents.media.control.mgcp.pkg.MgcpSignal;
import org.mobicents.media.control.mgcp.pkg.au.AudioSignalType;
import org.mobicents.media.control.mgcp.pkg.au.EndSignal;
import org.mobicents.media.control.mgcp.pkg.au.PlayAnnouncement;
import org.mobicents.media.control.mgcp.pkg.au.pc.PlayCollect;
import org.mobicents.media.control.mgcp.pkg.au.pr.PlayRecord;

/**
 * Abstract representation of an MGCP Endpoint that groups connections by calls.
 * 
 * @author Henrique Rosa (henrique.rosa@telestax.com)
 *
 */
public class GenericMgcpEndpoint implements MgcpEndpoint, MgcpCallListener, MgcpConnectionListener {

    private static final Logger log = Logger.getLogger(GenericMgcpEndpoint.class);

    // MGCP Components
    private final MgcpConnectionProvider connectionProvider;
    protected final MediaGroup mediaGroup;

    // Endpoint Properties
    private final EndpointIdentifier endpointId;
    private final NotifiedEntity defaultNotifiedEntity;
    private final ConcurrentHashMap<Integer, MgcpCall> calls;

    // Endpoint State
    private final AtomicBoolean active;

    // Events and Signals
    private NotificationRequest notificationRequest;
    protected MgcpSignal signal;

    // Observers
    private final Collection<MgcpEndpointObserver> endpointObservers;
    private final Collection<MgcpMessageObserver> messageObservers;

    public GenericMgcpEndpoint(EndpointIdentifier endpointId, MgcpConnectionProvider connectionProvider, MediaGroup mediaGroup) {
        // MGCP Components
        this.connectionProvider = connectionProvider;

        // Endpoint Properties
        this.endpointId = endpointId;
        this.defaultNotifiedEntity = new NotifiedEntity();
        this.calls = new ConcurrentHashMap<>(10);

        // Endpoint State
        this.active = new AtomicBoolean(false);

        // Media Components
        this.mediaGroup = mediaGroup;

        // Observers
        this.endpointObservers = new CopyOnWriteArrayList<>();
        this.messageObservers = new CopyOnWriteArrayList<>();
    }

    @Override
    public EndpointIdentifier getEndpointId() {
        return this.endpointId;
    }

    @Override
    public MediaGroup getMediaGroup() {
        return this.mediaGroup;
    }

    public boolean hasCalls() {
        return !this.calls.isEmpty();
    }

    @Override
    public MgcpConnection getConnection(int callId, int connectionId) {
        MgcpCall call = this.calls.get(callId);
        return (call == null) ? null : call.getConnection(connectionId);
    }

    private void registerConnection(int callId, MgcpConnection connection) {
        // Retrieve corresponding call
        MgcpCall call = calls.get(callId);
        if (call == null) {
            // Attempt to insert a new call
            call = new MgcpCall(callId);
            MgcpCall oldCall = this.calls.putIfAbsent(callId, call);

            // Drop newly create call and use existing one
            // This is possible because we are working in non-blocking concurrent scenario
            if (oldCall != null) {
                call = oldCall;
            }
        }

        // Store connection under call
        call.addConnection(connection);

        // Warn child class that connection was created
        onConnectionCreated(connection);

        // Activate endpoint on first registered connection
        if (!isActive()) {
            activate();
        }
    }

    @Override
    public MgcpConnection createConnection(int callId, boolean local) {
        MgcpConnection connection = local ? this.connectionProvider.provideLocal() : this.connectionProvider.provideRemote();
        registerConnection(callId, connection);
        if (!connection.isLocal()) {
            ((MgcpRemoteConnection) connection).setConnectionListener(this);
        }
        return connection;
    }

    @Override
    public MgcpConnection deleteConnection(int callId, int connectionId) throws MgcpCallNotFoundException, MgcpConnectionNotFound {
        MgcpCall call = this.calls.get(callId);
        if (call == null) {
            throw new MgcpCallNotFoundException("Call " + callId + " was not found.");
        } else {
            // Unregister connection
            MgcpConnection connection = call.removeConnection(connectionId);

            if (connection == null) {
                throw new MgcpConnectionNotFound("Connection " + Integer.toHexString(connectionId) + " was not found in call " + callId);
            } else {
                // Unregister call if it contains no more connections
                if (call.hasConnections()) {
                    this.calls.remove(callId);
                }

                // Warn child class that connection was deleted
                onConnectionDeleted(connection);

                // Set endpoint state
                if (!hasCalls()) {
                    deactivate();
                }

                // Close connection
                try {
                    connection.close();
                } catch (MgcpConnectionException e) {
                    log.error(this.endpointId + ": Connection " + connection.getHexIdentifier() + " was not closed properly", e);
                }

                return connection;
            }
        }
    }

    private List<MgcpConnection> deleteConnections(MgcpCall call) {
        List<MgcpConnection> connections = call.removeConnections();
        for (MgcpConnection connection : connections) {
            // Close connection
            try {
                connection.close();
            } catch (MgcpConnectionException e) {
                log.error(this.endpointId + ": Connection " + connection.getHexIdentifier() + " was not closed properly", e);

            }
        }
        return connections;
    }

    @Override
    public List<MgcpConnection> deleteConnections(int callId) throws MgcpCallNotFoundException {
        // De-register call from active sessions
        MgcpCall call = this.calls.remove(callId);
        if (call == null) {
            throw new MgcpCallNotFoundException("Call " + callId + " was not found.");
        } else {
            // Delete all connections from call
            List<MgcpConnection> connections = deleteConnections(call);

            // Set endpoint state
            if (!hasCalls()) {
                deactivate();
            }
            return connections;
        }
    }

    @Override
    public List<MgcpConnection> deleteConnections() {
        List<MgcpConnection> connections = new ArrayList<>();
        Iterator<MgcpCall> iterator = this.calls.values().iterator();
        while (iterator.hasNext()) {
            // Remove call from active call list
            MgcpCall call = iterator.next();
            iterator.remove();

            // Close connections
            connections.addAll(deleteConnections(call));
        }

        // Set endpoint state
        if (!hasCalls()) {
            deactivate();
        }
        return connections;
    }

    @Override
    public void onCallTerminated(MgcpCall call) {
        this.calls.remove(call.getId());
    }

    public boolean isActive() {
        return this.active.get();
    }

    private void activate() throws IllegalStateException {
        if (this.active.get()) {
            throw new IllegalArgumentException("Endpoint " + this.endpointId + " is already active.");
        } else {
            this.active.set(true);
            onActivated();
            notify(this, MgcpEndpointState.ACTIVE);
        }
    }

    protected void deactivate() throws IllegalStateException {
        if (this.active.get()) {
            this.active.set(false);
            onDeactivated();
            notify(this, MgcpEndpointState.INACTIVE);
        } else {
            throw new IllegalArgumentException("Endpoint " + this.endpointId + " is already inactive.");
        }
    }

    @Override
    public void onConnectionFailure(MgcpConnection connection) {
        // Find call that holds the connection
        Iterator<MgcpCall> iterator = this.calls.values().iterator();
        while (iterator.hasNext()) {
            MgcpCall call = iterator.next();
            MgcpConnection removed = call.removeConnection(connection.getIdentifier());

            // Found call where connection was contained
            if (removed != null) {
                // Unregister call if it does not contain any connections
                if (!call.hasConnections()) {
                    iterator.remove();
                }

                // Warn child implementations that a connection was deleted
                onConnectionDeleted(connection);

                // Deactivate endpoint if there are no active calls
                if (!hasCalls()) {
                    deactivate();
                }
            }
        }
    }

    @Override
    public void requestNotification(NotificationRequest request) throws MgcpSignalException {
        // Set new notification request and start executing requested signals (if any)
        this.notificationRequest = request;
        MgcpSignal signal = this.notificationRequest.peekSignal();
        if (signal != null) {
            if (signal.getClass().equals(EndSignal.class)) {
                // Preserve current signal
                ((EndSignal) signal).setEndpoint(this);
                signal.observe(this);
                signal.execute();
                this.signal = this.notificationRequest.pollSignal();
            } else if (this.signal != null && this.signal.isExecuting()) {
                throw new MgcpSignalException(this.signal.getClass().getSimpleName() + " still executing");
            } else {
                this.signal = this.notificationRequest.pollSignal();
                this.signal.observe(this);
                this.signal.execute();
            }
        } else {
            this.signal = this.notificationRequest.pollSignal();
        }
    }

    private NotifiedEntity resolve(NotifiedEntity value, NotifiedEntity defaultValue) {
        if (value != null) {
            return value;
        }
        return defaultValue;
    }

    /**
     * Event that is called when a new connection is created in the endpoint. <br>
     * <b>To be overridden by subclasses.</b>
     * 
     * @param connection
     */
    protected void onConnectionCreated(MgcpConnection connection) {
    }

    /**
     * Event that is called when a new connection is deleted in the endpoint. <br>
     * <b>To be overriden by subclasses.</b>
     * 
     * @param connection
     */
    protected void onConnectionDeleted(MgcpConnection connection) {
    }

    /**
     * Event that is called when endpoint becomes active. <br>
     * <b>To be overriden by subclasses.</b>
     * 
     * @param connection
     */
    protected void onActivated() {
    }

    /**
     * Event that is called when endpoint becomes inactive. <br>
     * <b>To be overriden by subclasses.</b>
     * 
     * @param connection
     */
    protected void onDeactivated() {
    }

    @Override
    public void observe(MgcpMessageObserver observer) {
        this.messageObservers.add(observer);

    }

    @Override
    public void forget(MgcpMessageObserver observer) {
        this.messageObservers.remove(observer);
    }

    @Override
    public void notify(Object originator, MgcpMessage message, MessageDirection direction) {
        Iterator<MgcpMessageObserver> iterator = this.messageObservers.iterator();
        while (iterator.hasNext()) {
            MgcpMessageObserver observer = (MgcpMessageObserver) iterator.next();
            if (observer != originator) {
                observer.onMessage(message, direction);
            }
        }
    }

    @Override
    public void onEvent(Object originator, MgcpEvent event) {
        // Verify if endpoint is listening for such event
        final String composedName = event.getPackage() + "/" + event.getSymbol();
        if (this.notificationRequest.isListening(composedName)) {
            // Unregister from current event
            this.signal.forget(this);

            // Build Notification
            MgcpRequest notify = new MgcpRequest();
            notify.setRequestType(MgcpRequestType.NTFY);
            notify.setTransactionId(0);
            notify.setEndpointId(this.endpointId.toString());
            notify.addParameter(MgcpParameterType.NOTIFIED_ENTITY, resolve(this.notificationRequest.getNotifiedEntity(), this.defaultNotifiedEntity).toString());
            notify.addParameter(MgcpParameterType.OBSERVED_EVENT, event.toString());
            notify.addParameter(MgcpParameterType.REQUEST_ID, notificationRequest.getRequestIdentifier());

            // Send notification to call agent
            notify(this, notify, MessageDirection.OUTGOING);
        }

        // Execute next event in pipeline
        this.signal = this.notificationRequest.pollSignal();
        if (this.signal != null) {
            try {
                this.signal.execute();
            } catch (MgcpSignalException e) {
                log.error(e);
            }
        } else {
            // No further events are scheduled. Cleanup notification request.
            this.notificationRequest = null;
        }
    }

    @Override
    public void observe(MgcpEndpointObserver observer) {
        this.endpointObservers.add(observer);
    }

    @Override
    public void forget(MgcpEndpointObserver observer) {
        this.endpointObservers.remove(observer);
    }

    @Override
    public void notify(MgcpEndpoint endpoint, MgcpEndpointState state) {
        Iterator<MgcpEndpointObserver> iterator = this.endpointObservers.iterator();
        while (iterator.hasNext()) {
            MgcpEndpointObserver observer = iterator.next();
            observer.onEndpointStateChanged(this, state);
        }
    }

    @Override
    public void cancelSignal(AudioSignalType signalType) throws MgcpSignalException {
        if (this.signal != null) {
            switch (signalType) {
                case PLAY_ANNOUNCEMENT:
                    if (!this.signal.getClass().equals(PlayAnnouncement.class)) {
                        throw new MgcpSignalException("Signal types are not compatible");
                    }
                    break;
                case PLAY_COLLECT:
                    if (!this.signal.getClass().equals(PlayCollect.class)) {
                        throw new MgcpSignalException("Signal types are not compatible");
                    }
                    break;
                case PLAY_RECORD:
                    if (!this.signal.getClass().equals(PlayRecord.class)) {
                        throw new MgcpSignalException("Signal types are not compatible");
                    }
                    break;
                default:
                    throw new MgcpSignalException("Unknown signal type");
            }
            if (this.signal.isExecuting()) {
                this.signal.cancel();
            }
        } else {
            throw new MgcpSignalException("No signal to be cancelled in the endpoint");
        }

    }

}
