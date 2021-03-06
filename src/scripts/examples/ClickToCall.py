import string, cgi, time, thread
import sys, urllib, urllib2
from BaseHTTPServer import BaseHTTPRequestHandler, HTTPServer

number1 = ""
number2 = ""

listeningPort   = 8081
listeningIp     = "127.0.0.1"
bluescaleIp     = "127.0.0.1"
bluescalePort   = 8080

class MyHandler(BaseHTTPRequestHandler):

    def do_GET(self):
        self.do_POST()

    def do_POST(self):
        if self.path == "/Status":
            self.printParams()
            self.postOK()
        else:
            self.printParams()
            self.connectCall()
        
    def printParams(self):
        params = self.parseParams()
        for field in params.keys():
            print( field + "=" + "".join(params[field]))
    
    def postOK(self):
        self.send_response(200)
        self.send_header("Content-Type", "text/html")
        self.end_headers()
        
    def parseParams(self):
        length = int(self.headers.getheader('Content-Length'))
        params = cgi.parse_qs(self.rfile.read(length), keep_blank_values=1)
        return params


    def connectCall(self):
        self.postOK()
        s = """
                <Response>
                    <Dial>
                        <Number>""" + number2 + """</Number>
                        <From>"""+ number1 + """</From>
                        <Action>http://""" + listeningIp + ":" + str(listeningPort) + """/Status</Action>
                    </Dial>
                </Response>
            """
        self.wfile.write(s)
        return
    

def main():
    try:
        server = HTTPServer( (listeningIp, listeningPort), MyHandler)
        print("going to connect " + number1 + " to " + number2)
        thread.start_new_thread(serveWeb, (server,))
        postCall()
        
        while True:
            time.sleep(5)

        #server.serve_forever()
        #time.sleep(5000)
    
    except Exception, err:
        print("damn error = " + str(err))

def serveWeb(server):
    server.serve_forever()
    print("serving...")

def postCall():
    data = urllib.urlencode( {"To" : number1, 
                              "From": number2, 
                              "Url" : "http://" + (listeningIp + ":" + str(listeningPort) + "/")} )
    f = urllib.urlopen( "http://" + bluescaleIp + ":" + str(bluescalePort) + "/Calls/" ,data)
    r = f.read()
    print(r)

if __name__ == '__main__':
    number1 = sys.argv[1]
    number2 = sys.argv[2]
    main()
