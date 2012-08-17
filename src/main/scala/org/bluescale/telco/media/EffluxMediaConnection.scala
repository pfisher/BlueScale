/*
*  
* This file is part of BlueScale.
*
* BlueScale is free software: you can redistribute it and/or modify
* it under the terms of the GNU Affero General Public License as published by
* the Free Software Foundation, either version 3 of the License, or
* (at your option) any later version.
* 
* BlueScale is distributed in the hope that it will be useful,
* but WITHOUT ANY WARRANTY; without even the implied warranty of
* MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
* GNU Affero General Public License for more details.
* 
* You should have received a copy of the GNU Affero General Public License
* along with BlueScale.  If not, see <http://www.gnu.org/licenses/>.
* 
* Copyright Vincent Marquez 2010
* 
* 
* Please contact us at www.BlueScale.org
*
*/

package org.bluescale.telco.media

import javax.sdp._

import org.bluescale.telco.SdpHelper;
import org.bluescale.telco.api._
import org.bluescale.telco._
import org.bluescale.util.BlueFuture
import java.io.InputStream
import com.biasedbit.efflux._
import com.biasedbit.efflux.packet.DataPacket
import com.biasedbit.efflux.participant.RtpParticipant
import com.biasedbit.efflux.participant.RtpParticipantInfo
import com.biasedbit.efflux.session._
import java.util.Timer
import java.util.TimerTask
import java.util.Date
import scala.collection.JavaConversions._

import java.util.Collection;
import java.util.ArrayList
import java.nio.ByteBuffer


case class RTPStreamInfo(delay:Int,
						startingtimestamp:Long,
						sequence:Int)


class EffluxMediaConnection(telco:TelcoServer) extends MediaConnection {
  
	private var connState = UNCONNECTED()
	
	private val payloadType = 0 //8 for Alaw
    private var _joinedTo:Option[Joinable[_]] = None
    private var _recordedFiles = List[String]()
    private var _playedFiles   = List[String]()
    
    override def playedFiles = _playedFiles 
	override def recordedFiles = _recordedFiles
    override def joinedTo = _joinedTo
    
    var jitterBuffer:Option[JitterBuffer] = None
    val rtpport = EffluxMediaConnection.getPort()
    val listeningSdp = SdpHelper.createSdp(payloadType, rtpport, telco.contactIp)
    val localparticipant = RtpParticipant.createReceiver(new RtpParticipantInfo(1), telco.listeningIp, rtpport, rtpport+1)
    var effluxSession: Option[SingleParticipantSession] = None
    var playTimer: Option[Timer] = None
    var streamInfo: Option[RTPStreamInfo] = None
    
    println("made a media connection, listening port = " + telco.listeningIp + " rtpPort = " + rtpport)
    println("Our connections listening sdp = " + listeningSdp)
 
    //TEMP debug vars
    var totalBytesRead = 0
    var totalPacketsread = 0
    
    def connectionState = connState
    
    private def initRtp(conn:Joinable[_]) {
    	val mediaport = SdpHelper.getMediaPort(conn.sdp) //
    	val remoteip = conn.sdp.getConnection().getAddress() //192.168.1.18
    	val dtmfPayloadType = 101
    	println("conn = " + conn + " address = " + remoteip + " Mediaport = " + mediaport + " localPort = " + rtpport)
    	println(" sd = " + conn.sdp)
    	jitterBuffer = Some(new JitterBuffer(8000,160, data=> {
    		MediaFileManager.addMedia(this, data)
    	}))
    	val remote1 = RtpParticipant.createReceiver(new RtpParticipantInfo(rtpport), remoteip, mediaport, mediaport+1)
    	val session1 = new SingleParticipantSession(this.toString, List(new Integer(payloadType), new Integer(dtmfPayloadType)), localparticipant, remote1, null, null)
    	effluxSession = Some(session1)
    	streamInfo = Some(RTPStreamInfo(20, new Date().getTime(), 1))
    	session1.addDataListener(getDataListener())
    	//println("STARTED THE RTP LISTENER on port" + rtpport + " remotePort =  " + mediaport + " For " + this)
   		session1.init()
    }
    
    override def join(conn:Joinable[_]) = BlueFuture(callback => {
    	//should we only do this when we get a 200 OK? should  we put it in the connect callback? 
    	initRtp(conn)
    	for (_ <- conn.connect(this, false)) {
    		this._joinedTo = Some(conn)
    		callback()
    	}
    })
    
    override def sendDtmf(digit:Int) {
    	for(joined <- joinedTo;
    		session <- effluxSession;
    		info <- streamInfo;
    		payloadType <- SdpHelper.getDtmfPayloadType(joined.sdp)) {
    			val packet = new DataPacket()
    			val data = new Array[Byte](4)
    			data(0) = digit.asInstanceOf[Byte]
    			println("sending the DTMF packet, payload = " + payloadType)
    			session.sendDataPacket(makePacket(data,info, payloadType))
    			streamInfo = Some(info.copy(sequence =info.sequence+1))
    	}
    }
    
    override def sdp = 
      listeningSdp

    def joinPlay(filestream:InputStream, conn:Joinable[_]) = BlueFuture( callback => { 
    	for(_ <- join(conn);
    		_ <- play(filestream))
    	  callback()
    })
    
    override def joinedMediaChange() {
    	//KILL THE OLD SESSION AND MAKE A NEW ONE.
    	effluxSession.foreach(_.terminate())
    	joinedTo.foreach( joined => initRtp(joined))
    }

    protected[telco] def unjoin() = BlueFuture(callback => {
    	Thread.sleep(1000)
    	jitterBuffer.foreach(j => j.cancel())
    	MediaFileManager.finishAddMedia(this).foreach(newFile => _recordedFiles = newFile :: _recordedFiles)
    	println(" ~~~~~~~~~unjoin, mc = " + this.hashCode() + " files count = " + _recordedFiles.size)
    	stopPlaying()
    	unjoinCallback.foreach(_(joinedTo.get,this))
    	callback()
    })

    def play(filestream:InputStream) = BlueFuture(f => {
    	val localport = 0
    	playTimer = Some(new Timer())
    	val outer = this
    	val data = new Array[Byte](160)
    	for(joined <- joinedTo;
    		session <- effluxSession;
    		timer <- playTimer;
    		info <- streamInfo) {
    	   		var read = filestream.read(data)//lets skip the first 160 bytes so we don't have to worry about the header for now
    	   		val timerTask = new TimerTask() {
    	   			def run() {
    	   				for (sinfo <- outer.streamInfo)
    	   					read match {
    	   						case -1 =>
    	   							timer.cancel()
    	   							f()
    	   						case _ => 
    	   							read = filestream.read(data)
    	   							session.sendDataPacket(makePacket(data,info))
    	   							outer.streamInfo = Some(sinfo.copy(sequence=sinfo.sequence+1))
    	   				}
    				}
    	   		}
    	   		timer.scheduleAtFixedRate(timerTask, 0, info.delay)
    	}
    })
    
    private def makePacket(data:Array[Byte],info:RTPStreamInfo, pltype:Int = payloadType): DataPacket = { 
    	val packet = new DataPacket()
    	packet.setPayloadType(pltype)
       	packet.setData(data)
       	packet.setSequenceNumber(info.sequence)
       	packet.setTimestamp(info.startingtimestamp+(info.sequence*info.delay*8)) //justin karnegas figured this bug out!
       	packet
    }
    
    private def stopPlaying() = 
      playTimer.foreach( t => t.cancel())
    
    override def cancel() = BlueFuture(callback => {
    	stopPlaying()
    	callback()
    }) 
    
    override protected[telco] def connect(join:Joinable[_], connectAnyMedia:Boolean ) = BlueFuture(callback => {//doesn't need to be here? 
    	initRtp(join)
    	_joinedTo = Some(join)
    	callback()
	})
    
    override protected[telco] def connect(join:Joinable[_])= connect(join, true)
    
    private def getDataListener() = 
      	new RtpSessionDataListener() {
    		
    		var prevDtmfTime: Option[Long] = None
    		
    		def dataPacketReceived(session: RtpSession,  participant: RtpParticipantInfo, packet: DataPacket) {
    			packet.getPayloadType match {
    			  case 0 =>
    			  		jitterBuffer.foreach( jb =>
    			  		jb.addToQueue(packet))   			  
    			  case _ =>
    			  		if(packet.getTimestamp() != prevDtmfTime.getOrElse(null)) {
    			  			dtmfEventHandler.foreach( _(DTMFEvent(packet.getDataAsArray()(0))))
    			  		}
    			  		prevDtmfTime = Some(packet.getTimestamp())
    			}
           	}
    	}
}


object EffluxMediaConnection {
	val myarray = new Array[Byte](2000)	
	val Max = 5000
	val Min = 2000
	
	def getPort(): Int = {
	  val ran = Math.random
	  val ret = Min + (ran * ((Max - Min) + 1))
	  println("returning for getPort = " + ret)
	  ret.asInstanceOf[Int]
	}
	
	def putBackPort(port:Int): Unit = {
			println("fix me")
	}
}


