/*
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
* Please contact us at www.BlueScaleSoftware.com
*
*/
package org.bluescale.telco.jainsip

import javax.sip._
import javax.sip.address._
import javax.sip.header._
import javax.sip.message._
import javax.sdp.MediaDescription
import javax.sdp.SdpException
import javax.sdp.SdpFactory
import javax.sdp.SdpParseException
import javax.sdp.SessionDescription
import javax.sdp.MediaDescription
import gov.nist.javax.sip.SipStackImpl;
import gov.nist.javax.sip.stack.ServerLog;

import java.util._
import java.net.InetAddress
import scala.actors.Actor 
import org.bluescale.telco._
import org.bluescale.telco.jainsip._ 
import org.bluescale.telco.api._  
import org.bluescale.util._

protected[jainsip] class JainSipInternal(telco:SipTelcoServer,
										val listeningIp:String,
										val contactIp:String,
										val port:Int,
										val destIp:String, 
										val destPort:Int) extends SipListener 
														 //with LogHelper
														 {
    /*
    	*/

	println("listeningIp = " + listeningIp)
	println("contactIp = " + contactIp)
    val sipFactory = SipFactory.getInstance()
	sipFactory.setPathName("gov.nist")
	val properties = new Properties()
	val transport = "udp"

	def debug(s:String) {
		println(s)
	}
	def log(s:String) {
			println(s)
	}

	def error(s:String) {
		//println(s)
	}
    
    	
 
    properties.setProperty("javax.sip.STACK_NAME", "BSSJainSip"+this.hashCode())//name with hashcode so we can have multiple instances started in one VM
	properties.setProperty("javax.sip.OUTBOUND_PROXY", destIp +  ":" + destPort + "/"+ transport)
	properties.setProperty("gov.nist.javax.sip.DEBUG_LOG","log/debug_log" + port + ".log.txt") //FIXME
	properties.setProperty("gov.nist.javax.sip.SERVER_LOG","log/server_log" + port + ".log.txt")
	properties.setProperty("gov.nist.javax.sip.CACHE_CLIENT_CONNECTIONS","false")
	properties.setProperty("gov.nist.javax.sip.TRACE_LEVEL", "ERROR")
	properties.setProperty("gov.nist.javax.sip.THREAD_POOL_SIZE", "1")	
    properties.setProperty("gov.nist.javax.sip.LOG_MESSAGE_CONTENT", "false") 

	val sipStack = sipFactory.createSipStack(properties)
	//sipStack.asInstanceOf[SipStackImpl].getServerLog().setTraceLevel(ServerLog.TRACE_NONE)
    debug("createSipStack " + sipStack)
	val headerFactory 	= sipFactory.createHeaderFactory()
	val addressFactory = sipFactory.createAddressFactory()
	val messageFactory = sipFactory.createMessageFactory()
	
	var udpListeningPoint:Option[ListeningPoint] = None 
	var sipProvider:Option[SipProvider] = None
	val inviteCreator = new InviteCreator(this)

	def start() {
		sipStack.start()
		udpListeningPoint = Some( sipStack.createListeningPoint(listeningIp, port, transport) )
        sipProvider = Some( sipStack.createSipProvider(udpListeningPoint.get) )
	    sipProvider.get.addSipListener(this)
	}

	def stop() {
		sipStack.stop()
		udpListeningPoint.foreach( sipStack.deleteListeningPoint(_) )
	}

    	
	override def processRequest(requestEvent:RequestEvent) {
		val request = requestEvent.getRequest()
		val serverTransactionId = requestEvent.getServerTransaction()
        println("--------------------------------- requestEvent, method = " + request.getMethod() ) 
		 request.getMethod() match {
		  case Request.INVITE 	=> processInvite(requestEvent)
		  case Request.ACK 		=> processAck(requestEvent, requestEvent.getRequest())
		  case Request.BYE 		=> processBye(requestEvent, requestEvent.getRequest(), requestEvent.getServerTransaction())
		  case Request.REGISTER => //processRegister(requestEvent)
		  case Request.CANCEL 	=> processCancel(requestEvent)		    
		    //TODO: handle cancels...may be too late for most things but we can try to cancel.
		  case _ => println("err, what?")
		            //serverTransactionId.sendResponse( messageFactory.createResponse( 202, request ) )
					// send one back
					//val prov = requestEvent.getSource()
					//val refer = requestEvent.getDialog().createRequest("REFER")
					//requestEvent.getDialog().sendRequest( prov.getNewClientTransaction(refer) )
					
		 }		 
	}
    
    private def processBye(requestEvent: RequestEvent, request:Request, transaction:ServerTransaction) : Unit = {
		transaction.sendResponse(messageFactory.createResponse(200, request))
		val conn = telco.getConnection(getCallId(request))
		conn.bye(transaction)
	}

	private def processRegister(requestEvent:RequestEvent) {
		val r = requestEvent.getRequest()
		printHeaders(requestEvent.getRequest())
		sendRegisterResponse(200, requestEvent)
    }

    private def processCancel(requestEvent:RequestEvent) {
    println("####### processCancel #########")
	    val request = requestEvent.getRequest()
	    val conn = telco.getConnection(getCallId(request))
	    conn.cancel(requestEvent.getServerTransaction()) //fixme, shouldn't be setting here!v
	}
  
	private def processInvite(requestEvent:RequestEvent) {
		val request = requestEvent.getRequest()
		Option(requestEvent.getServerTransaction) match {
			
			case Some(transaction) =>   
			    val conn = telco.getConnection(getCallId(request))
                val sdp = SdpHelper.getSdp(request.getRawContent())
			    conn.reinvite(transaction, sdp)
			case None => 	    
			    
			    val transaction = requestEvent.getSource().asInstanceOf[SipProvider].getNewServerTransaction(request)
			    //TODO: should we respond with progressing, and only ringing if the user does something? 
				transaction.sendResponse(messageFactory.createResponse(Response.RINGING,request) )
				//transaction.sendResponse(messageFactory.createResponse(Response.TRYING, request))

				val destination = parseToHeader(request.getRequestURI().toString())
				val conn = new JainSipConnection(getCallId(request), destination, "", INCOMING(), telco, true)
                val sdp = SdpHelper.getSdp(request.getRawContent())
                telco.addConnection(conn) 
                conn.invite(transaction,sdp)
                println("------- telco.fireINCOMIGN here")
                telco.fireIncoming(conn)//todo: move to connection?
    	}
	}

	 
  	
  	private def processAck(requestEvent:RequestEvent, request:Request) { 
		val request = requestEvent.getRequest()
  	    val conn = telco.getConnection(getCallId(request))//FIXME: return an option and foreach on it... prevent NPE
  	    conn.ack(requestEvent.getServerTransaction())
  	    //conn.setUAS(requestEvent.getServerTransaction, ...)

  	    //how do we deal with ackS? 
  	    //conn.setSipState(
    	//conn.execute(()=>conn.setState( VERSIONED_CONNECTED(conn.serverTx.get.getBranchId() )))
	}  	
   
   //TODO: handle re-tries... we can't just let another setState happen, could fuck things up if something else had been set already...
	override def processResponse(re:ResponseEvent) {
		var transaction = re.getClientTransaction()
		val conn = telco.getConnection(getCallId(re))
		if ( null == transaction) { //we already got a 200OK and the TX was terminated...
			debug(" transaction is null right away re = " + re.getDialog())
			return 
		}
		val cseq = asResponse(re).getHeader(CSeqHeader.NAME).asInstanceOf[CSeqHeader]
		val statusCode = asResponse(re).getStatusCode()

		//conn.execute(()=>{
		statusCode match {
			case Response.SESSION_PROGRESS => //conn.setState(VERSIONED_PROGRESSING("") )

			case Response.RINGING =>
			                //conn.dialog = Some(re.getDialog())
	 		                Option(asResponse(re).getRawContent()).foreach( content=> {
    			                val sdp = SdpHelper.getSdp(content)
			                    if (!SdpHelper.isBlankSdp(sdp)) {
	    		                    conn.setUAC(transaction,statusCode, sdp)       
	    		                }
                            }) 
			                           
			case Response.OK => 
		    			cseq.getMethod() match {
		    				case Request.INVITE =>
		    				  			val ackRequest = transaction.getDialog().createAck( cseq.getSeqNumber() )
		    							transaction.getDialog().sendAck(ackRequest)//should be done in the call? 
		    							val sdp = SdpHelper.getSdp(asResponse(re).getRawContent())
				  					    conn.setUAC(transaction, statusCode, sdp)	
				  					    				  						    	
		    				case Request.CANCEL =>
		    				    //cancel, removeConnection,
		    				    println(">>>>>>>>>>>>>>>>>>> GOT A 200 for a cancel ")
		    				    conn.setUAC(transaction, statusCode, conn.sdp) 
		    				    //conn.setState(VERSIONED_CANCELED( transaction.getBranchId() ) )
		    					log("	cancel request ")
		    				case Request.BYE =>
		    					telco.removeConnection(conn)
		    					conn.setUAC(transaction, statusCode, conn.sdp)
		    					//conn./setState( VERSIONED_UNCONNECTED(transaction.getBranchId() ) )	
    	    				case _ => 
			    			  	error("	wtf ")
		    			}
		    case Response.REQUEST_TERMINATED =>
		        println("TERMINATED")
			case _ => error("Unexpected Response = " + asResponse(re).getStatusCode())
		}
		//})
	}

	def sendRegisterResponse(responseCode:Int, requestEvent:RequestEvent) = {
		val response = messageFactory.createResponse(responseCode, requestEvent.getRequest() ) //transaction.getRequest())
		response.addHeader( requestEvent.getRequest().getHeader("Contact"))
		requestEvent.getServerTransaction().sendResponse(response)
	}
 
	//TODO: deal with dead transactions...
	def sendResponse(responseCode:Int, tx:ServerTransaction, content:Array[Byte] ) { 
	    val response = messageFactory.createResponse(responseCode,tx.getRequest)
		//response.getHeader(ToHeader.NAME).asInstanceOf[ToHeader].setTag("4321")  //FIXME
	    response.addHeader(headerFactory.createContactHeader(addressFactory.createAddress("sip:" + contactIp + ":"+port)))
        if ( null != content ) response.setContent(content,headerFactory.createContentTypeHeader("application", "sdp"))
		tx.sendResponse(response)
    }

    /*
	def handleMedia(conn:JainSipConnection, re:ResponseEvent) {
        SdpHelper.addMediaTo( conn.sdp, SdpHelper.getSdp(asResponse(re).getRawContent()) )
	}
	*/

    def sendCancel(clientTx:ClientTransaction) : ClientTransaction = {
    println("SENDING CANCEL")
        val request = clientTx.createCancel()
        val tx = sipProvider.get.getNewClientTransaction(request)
        tx.sendRequest()
        return tx
    }
	
	def sendInvite(conn:JainSipConnection, sdp:SessionDescription) : (String,ClientTransaction) = {
		val request = inviteCreator.getInviteRequest(conn.origin, conn.destination, sdp.toString().getBytes())
		//FIXME: add FROM
		request.addHeader(inviteCreator.getViaHeader().get(0))
		//conn.contactHeader = Some(request.getHeader("contact").asInstanceOf[ContactHeader])
		val tx =  sipProvider.get.getNewClientTransaction(request)
		val id = getCallId(request)
		tx.sendRequest()
		return (id, tx)
	}

    def sendReinvite(tx:ClientTransaction, sdp:SessionDescription) : ClientTransaction = {
		//log("SDP = " + sdp)
		///how to get the dialog? from the transaction?
        val request = tx.getDialog().createRequest(Request.INVITE)
        request.removeHeader("contact")//The one from the createRequest is the listeningIP..., same with the via
        request.removeHeader("via")
        request.addHeader(inviteCreator.getViaHeader().get(0))
		request.addHeader(headerFactory.createContactHeader(addressFactory.createAddress("sip:" + contactIp + ":" + port)))
		val contentTypeHeader = headerFactory.createContentTypeHeader("application", "sdp")
		request.setContent(sdp.toString().getBytes(), contentTypeHeader)
	    val newTx = sipProvider.get.getNewClientTransaction(request) 
   		tx.getDialog().sendRequest(newTx)
   		return newTx
	}
 
    //should we return the bye here?  I think certain things should be un-cancellable, so OK...
	def sendByeRequest(conn:JainSipConnection) : ClientTransaction = {
        val tx = conn.clientTx.getOrElse( conn.serverTx.getOrElse( throw new Exception("BLAHL")))
        val byeRequest = tx.getDialog().createRequest(Request.BYE)
        val newTx =	sipProvider.get.getNewClientTransaction(byeRequest)
        tx.getDialog().sendRequest(newTx)
        return newTx
	} 

	override def processTimeout(timeout:TimeoutEvent) {
	    error("==================================================r processTimeout!")
	}
 
	override def processIOException(ioException:IOExceptionEvent) {
	    error("processIOException!")
	}
 
	override def processTransactionTerminated(transactionTerminated:TransactionTerminatedEvent) {
	    error("processTransactionTerminated")
	}
 
	override def processDialogTerminated(dialogTerminated:DialogTerminatedEvent) {
	    error("processDialogTerminated")
	}
    
  	protected[jainsip] def getCallId(request:Request) = request.getHeader(CallIdHeader.NAME).asInstanceOf[CallIdHeader].getCallId()
	
   	protected[jainsip] def asResponse(r:ResponseEvent) = r.getResponse().asInstanceOf[Response]
       
  	protected[jainsip] def getCallId(r:ResponseEvent) = asResponse(r).
  														getHeader(CallIdHeader.NAME).asInstanceOf[CallIdHeader].getCallId()
  	   
  	protected[jainsip] def asRequest(r:RequestEvent) = r.getRequest()
  	 
  	private def parseToHeader(to:String): String = {
        //TODO: fix case where there is no callerID
        to.toString().split("@")(0).split(":")(1)
  	}
   
  	
  	private def printHeaders(request:Request) = { 
  		val iter = request.getHeaderNames()
		while (iter.hasNext()) {
			val headerName = iter.next().toString()
			println("  h = " + headerName + "=" + request.getHeader(headerName))
		}
  	} 

}
