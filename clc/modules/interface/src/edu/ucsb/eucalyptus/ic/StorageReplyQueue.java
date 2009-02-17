/*
 * Software License Agreement (BSD License)
 *
 * Copyright (c) 2008, Regents of the University of California
 * All rights reserved.
 *
 * Redistribution and use of this software in source and binary forms, with or
 * without modification, are permitted provided that the following conditions
 * are met:
 *
 * * Redistributions of source code must retain the above
 *   copyright notice, this list of conditions and the
 *   following disclaimer.
 *
 * * Redistributions in binary form must reproduce the above
 *   copyright notice, this list of conditions and the
 *   following disclaimer in the documentation and/or other
 *   materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 *
 * Author: Sunil Soman sunils@cs.ucsb.edu
 */

package edu.ucsb.eucalyptus.ic;

import edu.ucsb.eucalyptus.cloud.*;
import edu.ucsb.eucalyptus.msgs.EucalyptusErrorMessageType;
import edu.ucsb.eucalyptus.msgs.EucalyptusMessage;
import edu.ucsb.eucalyptus.msgs.StorageErrorMessageType;
import edu.ucsb.eucalyptus.transport.binding.BindingManager;
import edu.ucsb.eucalyptus.util.ReplyCoordinator;
import org.apache.http.HttpStatus;
import org.apache.log4j.Logger;
import org.mule.message.ExceptionMessage;

public class StorageReplyQueue {

    private static Logger LOG = Logger.getLogger( StorageReplyQueue.class );

    private static ReplyCoordinator replies = new ReplyCoordinator( 3600000 );

    public void handle( EucalyptusMessage msg )
    {
        Logger.getLogger( StorageReplyQueue.class ).warn( "storage queueing reply to " + msg.getCorrelationId() );
        replies.putMessage( msg );
    }

    public void handle( ExceptionMessage muleMsg )
    {
        try
        {
            Object requestMsg = muleMsg.getPayload();
            String requestString = requestMsg.toString();
            EucalyptusMessage msg = ( EucalyptusMessage ) BindingManager.getBinding( "msgs_eucalyptus_ucsb_edu" ).fromOM( requestString );
            Throwable ex = muleMsg.getException().getCause();
            EucalyptusMessage errMsg;

            if ( ex instanceof NoSuchVolumeException )
            {
                errMsg = new StorageErrorMessageType( "NoSuchVolume", "Volume not found", HttpStatus.SC_NOT_FOUND, msg.getCorrelationId());
                errMsg.setCorrelationId( msg.getCorrelationId() );
            }
            else if ( ex instanceof VolumeInUseException )
            {
                errMsg = new StorageErrorMessageType( "VolumeInUse", "Volume in use", HttpStatus.SC_FORBIDDEN, msg.getCorrelationId());
                errMsg.setCorrelationId( msg.getCorrelationId() );
            }
            else if ( ex instanceof NoSuchSnapshotException )
            {
                errMsg = new StorageErrorMessageType( "NoSuchSnapshot", "Snapshot not found", HttpStatus.SC_NOT_FOUND, msg.getCorrelationId());
                errMsg.setCorrelationId( msg.getCorrelationId() );
            }
            else if ( ex instanceof VolumeAlreadyExistsException )
            {
                errMsg = new StorageErrorMessageType( "VolumeAlreadyExists", "Volume already exists", HttpStatus.SC_CONFLICT, msg.getCorrelationId());
                errMsg.setCorrelationId( msg.getCorrelationId() );
            }
            else if ( ex instanceof VolumeNotReadyException )
            {
                errMsg = new StorageErrorMessageType( "VolumeNotReady", "Volume not ready yet", HttpStatus.SC_CONFLICT, msg.getCorrelationId());
                errMsg.setCorrelationId( msg.getCorrelationId() );
            }
            else if ( ex instanceof SnapshotInUseException )
            {
                errMsg = new StorageErrorMessageType( "SnapshotInUse", "Snapshot in use", HttpStatus.SC_CONFLICT, msg.getCorrelationId());
                errMsg.setCorrelationId( msg.getCorrelationId() );
            }
            else
            {
                errMsg = new EucalyptusErrorMessageType( muleMsg.getComponentName() , msg, ex.getMessage());
            }
            replies.putMessage( errMsg );
        }
        catch ( Exception e )
        {
            LOG.error(e);
        }
    }

    public static EucalyptusMessage getReply( String msgId )
    {
        Logger.getLogger( StorageReplyQueue.class ).warn( "storage request for reply to " + msgId );
        EucalyptusMessage msg = replies.getMessage( msgId );
        Logger.getLogger( StorageReplyQueue.class ).warn( "storage obtained reply to " + msgId );
        return msg;
    }
}