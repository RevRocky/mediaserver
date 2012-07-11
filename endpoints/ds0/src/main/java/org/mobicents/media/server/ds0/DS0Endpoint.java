/*
 * JBoss, Home of Professional Open Source
 * Copyright 2011, Red Hat, Inc. and individual contributors
 * by the @authors tag. See the copyright.txt in the distribution for a
 * full listing of individual contributors.
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
package org.mobicents.media.server.ds0;

import java.util.ArrayList;
import org.mobicents.media.Component;
import org.mobicents.media.MediaSink;
import org.mobicents.media.MediaSource;
import org.mobicents.media.server.spi.MediaType;
import org.mobicents.media.server.spi.ResourceUnavailableException;
import org.mobicents.media.server.spi.TooManyConnectionsException;
import org.mobicents.media.server.component.Splitter;
import org.mobicents.media.server.component.audio.AudioMixer;
import org.mobicents.media.server.impl.PipeImpl;
import org.mobicents.media.server.BaseEndpointImpl;
import org.mobicents.media.server.BaseSS7EndpointImpl;
import org.mobicents.media.server.spi.Connection;
import org.mobicents.media.server.spi.ConnectionType;
import org.mobicents.media.server.io.ss7.SS7Manager;
import org.mobicents.media.server.io.ss7.SS7DataChannel;
import org.mobicents.media.server.impl.resource.phone.PhoneSignalGenerator;
import org.mobicents.media.server.impl.resource.phone.PhoneSignalDetector;
/**
 *
 * @author oifa yulian
 */
public class DS0Endpoint extends BaseSS7EndpointImpl {

    private SS7DataChannel ss7DataChannel;
    
    private AudioMixer audioMixer;    
    private Splitter audioSplitter;
    
    private ArrayList<Component> components = new ArrayList();
    
    private MediaSink sink;
    private MediaSource source;
    
    public DS0Endpoint(String name,SS7Manager ss7Manager,int channelID,boolean isALaw) {
        super(name,BaseEndpointImpl.ENDPOINT_NORMAL,ss7Manager,channelID,isALaw);
        
        try
        {
        	ss7DataChannel=new SS7DataChannel(ss7Manager,channelID,isALaw);
        }
        catch(Exception e)
        {
        }
    }
    
    @Override
    public MediaSink getSink(MediaType media) {
        switch(media) {
            case AUDIO : 
            	return sink;
            default : 
            	return null;
        }
    }

    @Override
    public MediaSource getSource(MediaType media) {
        switch (media) {
            case AUDIO:
            	return source;
            default:
                return null;
        }
    }
    
    @Override
    public void start() throws ResourceUnavailableException {
    	try {
    		audioMixer = new AudioMixer(getScheduler());
    		sink = audioMixer.newInput();
    		
    		//connect phone signal generator
    		PhoneSignalGenerator phoneGenerator=new PhoneSignalGenerator("phone generator",getScheduler());
    		PipeImpl p = new PipeImpl();
    		p.connect(phoneGenerator);
    		p.connect(audioMixer.newInput());
    		components.add(phoneGenerator);
    		
    		p = new PipeImpl();
    		p.connect(audioMixer.getOutput());
    		p.connect(ss7DataChannel.getOutput());
    		
    		audioSplitter = new Splitter(getScheduler());    		
    		
    		//connect phone signal detector
    		source=audioSplitter.newOutput();
    		PhoneSignalDetector phoneDetector=new PhoneSignalDetector("phone detector",getScheduler(),source);
    		PipeImpl p1 = new PipeImpl();
    		p1.connect(phoneDetector);
    		p1.connect(source);    		
    		components.add(phoneDetector);
    		
    		source=audioSplitter.newOutput();
    		p1 = new PipeImpl();
    		p1.connect(ss7DataChannel.getInput());
    		p1.connect(audioSplitter.getInput());    		
    		
    		
    		audioMixer.getOutput().start();
    		audioSplitter.getInput().start();
    		
        	ss7DataChannel.getInput().setDsp(getDspFactory().newProcessor());
            ss7DataChannel.getOutput().setDsp(getDspFactory().newProcessor());            

            components.add(ss7DataChannel.getInput());
            components.add(ss7DataChannel.getOutput());
            //create player , connect it with pipe to output            
        } catch (Exception e) {
            throw new ResourceUnavailableException(e);
        }

        super.start();
    }
    
    @Override
    public Component getResource(MediaType mediaType, Class intf) {    
        switch (mediaType) {
            case AUDIO:
            	for (int i = 0; i < components.size(); i++) {
                    if (components.get(i).getInterface(intf) != null) {
                        return components.get(i);
                    }
                }
            default:
                return null;
        }
    }
    
    @Override
    public void configure(boolean isALaw)
    {
    	ss7DataChannel.setCodec(isALaw);
    }
    
    @Override
    public void unblock() {
    }

    @Override
    public void block() {
    }
    
    @Override
    public Connection createConnection(ConnectionType type,boolean isLocal) throws TooManyConnectionsException, ResourceUnavailableException {
    	Connection connection=super.createConnection(type,isLocal);
    	
    	if(getActiveConnectionsCount()==1)
    		ss7DataChannel.bind();
    	
    	return connection;
    }
    
    @Override
    public void deleteConnection(Connection connection) {
    	super.deleteConnection(connection);
    	
    	if(getActiveConnectionsCount()==0)
    		ss7DataChannel.close();    	
    }
    
    @Override
    public void deleteAllConnections() {
    	super.deleteAllConnections();
    	
    	if(getActiveConnectionsCount()==0)
    		ss7DataChannel.close();    	
    }
}