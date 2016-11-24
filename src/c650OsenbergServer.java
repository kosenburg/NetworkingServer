import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.net.*;
import java.util.Arrays;
import java.util.Scanner;
import java.util.concurrent.*;

public class c650OsenbergServer {
    private static final int PORTNUMBER = 21252;
    private static final ExecutorService threadPool = Executors.newFixedThreadPool(4);
    private ServerSocket serverSocket;
    private Socket clientSocket;
    private int timeout;
    private byte packetSize;
    private int udpPort = 0;



    public c650OsenbergServer(int timeout, byte packetSize) throws IOException {
        this.timeout = timeout;
        this.packetSize = packetSize;
        serverSocket = new ServerSocket(PORTNUMBER);
    }


    public void startServer() throws IOException {
        while(true) {
            clientSocket = serverSocket.accept();
            readSocketData();
            if (udpPort != 0) {
                threadPool.submit(new fileTransferHandler(udpPort, packetSize, timeout));
            }
            clientSocket.close();
        }
    }

    private void readSocketData() throws IOException { // possible cause if repeat send
        BufferedReader input = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));
        String line;
        while((line = input.readLine()) != null) {
            String[] sentInfo = line.split(",");
            udpPort = Integer.parseInt(sentInfo[1]);
            System.out.println("Hello Received " + udpPort);
        }
    }

    public static void main(String[] args) {

        int timeout;
        byte packetSize;
        try (Scanner input = new Scanner(System.in)) {
            System.out.println("Please enter the timeout: ");
            timeout = input.nextInt();
            System.out.println("Please enter the packet size (bytes): ");
            packetSize = input.nextByte();
        }
        try {
            c650OsenbergServer server = new c650OsenbergServer(timeout, packetSize);
            server.startServer();
        } catch (IOException e) {
            System.err.println("Connection error due to: " + e.getMessage());
        }
    }
}

class fileTransferHandler implements Runnable {
    private static final String HOSTNAME = "localhost";
    private static final String FILEPATH = "set your path to file transfer here";
    private static final int defaultInfoBytesNum = 2;
    private ExecutorService sendingThread;
    private DatagramSocket udpSocket;
    private int clientPort;
    private byte packetSize;
    private File file;
    private byte[] fileData;
    private int packetNumber;
    private int fileBytePointer;
    private boolean ackNotRecieved = true;
    private int timeout;
    Future<String> myStuff;
    private int ackCounter;
    private int changingTimeout;

    public fileTransferHandler(int suppliedPort, byte packetSize, int timeout) throws IOException {
        setFileForTransfer();
        openUpdSocket();
        setPacketProperties(suppliedPort, packetSize, timeout);
    }

    private void setFileForTransfer() throws IOException {
        setFile();
        setFileBytes();
    }

    private void setFile() {
        file = new File(FILEPATH);
    }

    private void setFileBytes() throws IOException {
        FileInputStream input = new FileInputStream(file);
        fileData = new byte[(int) file.length()];
        input.read(fileData,0,fileData.length);
    }

    private void openUpdSocket() throws SocketException {
        udpSocket = new DatagramSocket();
    }

    private void setPacketProperties(int suppliedPort, byte packetSize, int timeout) {
        this.clientPort = suppliedPort;
        this.packetSize = packetSize;
        this.packetNumber = 0;
        this.timeout = timeout;
        this.changingTimeout = timeout;
    }

    @Override
    public void run() {
        try {
            while(ackNotRecieved) {
                ackCounter = 0;
                System.out.println("Sending again..");
                startSendCycle();
                pauseExecutionForAck();
            }
            sendTerminationPacket();
        } catch (IOException e) {
            System.err.println(e.getMessage());
        } finally {
            udpSocket.close();
        }
    }

    private void startSendCycle() throws IOException {
        sendingThread = Executors.newSingleThreadExecutor();
        myStuff = sendingThread.submit(new Callable<String>() {
            @Override
            public String call() throws IOException {
                sendFilePackets();
                while(true) {
                    listenForAck();
                }

            }
        });
    }

    private void sendFilePackets() throws IOException {
        fileBytePointer = 0;
        packetNumber = 1;
        while (fileBytePointer < fileData.length) {
            byte[] temporary = getDataComponent();
            DatagramPacket sendPacket = new DatagramPacket(temporary, temporary.length, InetAddress.getByName(HOSTNAME), clientPort);
            udpSocket.send(sendPacket);

            System.out.println("Sent packet:" + packetNumber);

            packetNumber += 1;
        }
    }

    private byte[] getDataComponent() {
        byte[] temporary = new byte[packetSize + 2];
        temporary[0] = (byte) packetNumber;
        temporary[1] = (byte) file.length();

        for (int i = defaultInfoBytesNum; i < temporary.length; i++) { // dfh = 2
            temporary[i] = fileData[fileBytePointer];
            fileBytePointer++;
            if (fileBytePointer >= fileData.length) {
                break;
            }
        }
        return temporary;
    }

    private void listenForAck() throws IOException {
        DatagramPacket ackPacket = new DatagramPacket(new byte[3], 3);
        udpSocket.receive(ackPacket);

        System.out.println("Message recieved");

        String message = new String(ackPacket.getData());

        if (message.equals("ack")) {
            ackCounter++;
        }
    }

    private void pauseExecutionForAck() {
        try {
            String ack = myStuff.get(changingTimeout,TimeUnit.MILLISECONDS); // never finished in time always fails to timeout exception
        } catch (InterruptedException | TimeoutException | ExecutionException e) {
            checkTimeoutResults();
            timeout = timeout * 2;
            myStuff.cancel(true);
        }
        sendingThread.shutdown();
    }

    private void checkTimeoutResults() {
        if (ackCounter == 1) {
            ackNotRecieved = false;
            System.out.println("OK " + clientPort);
            changingTimeout = timeout;
        } else if (ackCounter == 2) {
            ackNotRecieved = false;
            System.out.println("Duplicate ack received " + clientPort);
            changingTimeout = timeout * 2;
        } else if (ackCounter == 0) {
            changingTimeout = changingTimeout * 2;
        }  else {
            ackNotRecieved = false;
            System.out.println("External case.");
        }
    }

    private void sendTerminationPacket() throws IOException {
        String message = "ok";

        DatagramPacket okayPacket = new DatagramPacket(message.getBytes(),message.getBytes().length, InetAddress.getByName(HOSTNAME), clientPort);
        udpSocket.send(okayPacket);
    }

}


