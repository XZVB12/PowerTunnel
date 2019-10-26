package ru.krlvm.powertunnel;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.HttpRequest;
import org.littleshoot.proxy.HttpFilters;
import org.littleshoot.proxy.HttpFiltersSourceAdapter;
import org.littleshoot.proxy.HttpProxyServer;
import org.littleshoot.proxy.impl.DefaultHttpProxyServer;
import ru.krlvm.powertunnel.data.DataStore;
import ru.krlvm.powertunnel.filter.ProxyFilter;
import ru.krlvm.powertunnel.frames.*;
import ru.krlvm.powertunnel.system.MirroredOutputStream;
import ru.krlvm.powertunnel.utilities.URLUtility;
import ru.krlvm.powertunnel.utilities.Utility;
import ru.krlvm.swingdpi.SwingDPI;

import javax.swing.*;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * PowerTunnel Bootstrap class
 *
 * This class initialized PowerTunnel, load government blacklist,
 * user lists, holds journal and controlling a LittleProxy Server
 *
 * @author krlvm
 */
public class PowerTunnel {

    public static String NAME = "PowerTunnel";
    public static String VERSION = "1.1";

    private static HttpProxyServer SERVER;
    private static boolean RUNNING = false;
    public static String SERVER_IP_ADDRESS = "127.0.0.1";
    public static int SERVER_PORT = 8085;

    public static final boolean FULL_OUTPUT_MIRRORING = false;

    private static final Map<String, String> JOURNAL = new LinkedHashMap<>();
    private static final SimpleDateFormat JOURNAL_DATE_FORMAT = new SimpleDateFormat("[HH:mm]: ");

    private static final Set<String> GOVERNMENT_BLACKLIST = new HashSet<>();
    private static final Set<String> ISP_STUB_LIST = new LinkedHashSet<>();
    private static final Set<String> USER_BLACKLIST = new LinkedHashSet<>();
    private static final Set<String> USER_WHITELIST = new LinkedHashSet<>();

    private static MainFrame frame;
    public static LogFrame logFrame;
    public static JournalFrame journalFrame;
    public static UserListFrame[] USER_FRAMES;

    public static void main(String[] args) {
        //Initialize UI
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ex) {
            System.out.println("Failed to set native Look and Feel: " + ex.getMessage());
            ex.printStackTrace();
            System.out.println();
        }
        SwingDPI.applyScalingAutomatically();

        //Initializing main frame and system outputs mirroring
        logFrame = new LogFrame();
        if(FULL_OUTPUT_MIRRORING) {
            PrintStream systemOutput = System.out;
            PrintStream systemErr = System.err;
            System.setOut(new PrintStream(new MirroredOutputStream(new ByteArrayOutputStream(), logFrame, systemOutput)));
            System.setErr(new PrintStream(new MirroredOutputStream(new ByteArrayOutputStream(), logFrame, systemErr)));
        }

        Utility.print(NAME + " version " + VERSION);
        Utility.print("Simple, scalable, cross-platform and effective solution against government censorship");
        Utility.print("https://github.com/krlvm/PowerTunnel");
        Utility.print("(c) krlvm, 2019");
        Utility.print();
        Utility.print("[#] You can specify IP and port: java -jar JAR_FILE_NAME.jar [IP] [PORT]");

        //Allow us to modify 'HOST' request header
        System.setProperty("sun.net.http.allowRestrictedHeaders", "true");

        //Parse launch arguments
        if(args.length > 0 && args.length < 3) {
            SERVER_IP_ADDRESS = args[0];
            if(args.length == 2) {
                try {
                    SERVER_PORT = Integer.parseInt(args[1]);
                } catch (NumberFormatException ex) {
                    Utility.print("[x] Invalid port number, using default");
                }
            }
        }

        journalFrame = new JournalFrame();
        frame = new MainFrame();

        //Initialize UI
        USER_FRAMES = new UserListFrame[] {
                new BlacklistFrame(), new WhitelistFrame()
        };
    }

    /**
     * PowerTunnel bootstrap
     */
    public static void bootstrap() {
        //Load data
        try {
            GOVERNMENT_BLACKLIST.addAll(new DataStore(DataStore.GOVERNMENT_BLACKLIST).load());
            USER_BLACKLIST.addAll(new DataStore(DataStore.USER_BLACKLIST).load());
            USER_WHITELIST.addAll(new DataStore(DataStore.USER_WHITELIST).load());
            ISP_STUB_LIST.addAll(new DataStore(DataStore.ISP_STUB_LIST).load());
            Utility.print("[i] Loaded '%s' government blocked sites, '%s' user blocked sites, '%s' user whitelisted sites",
                    GOVERNMENT_BLACKLIST.size(), USER_BLACKLIST.size(), USER_WHITELIST.size());
        } catch (IOException ex) {
            Utility.print("[x] Failed to load data store: " + ex.getMessage());
            ex.printStackTrace();
        }
        Utility.print();

        //Start server
        startServer();
    }

    /**
     * Starts LittleProxy server
     */
    private static void startServer() {
        Utility.print("[.] Starting LittleProxy server on %s:%s", SERVER_IP_ADDRESS, SERVER_PORT);
        try {
            SERVER = DefaultHttpProxyServer.bootstrap().withFiltersSource(new HttpFiltersSourceAdapter() {
                @Override
                public HttpFilters filterRequest(HttpRequest originalRequest, ChannelHandlerContext ctx) {
                    return new ProxyFilter(originalRequest);
                }
            }).withAddress(new InetSocketAddress(InetAddress.getByName(SERVER_IP_ADDRESS), SERVER_PORT)).start();
            RUNNING = true;
        } catch (UnknownHostException ex) {
            Utility.print("[?] Cannot use IP-Address '%s': %s", SERVER_IP_ADDRESS, ex.getMessage());
            ex.printStackTrace();
            System.out.println();
            Utility.print("[!] Program halted");
        }
        Utility.print("[.] Server started");
        Utility.print();

        frame.update();
    }

    /**
     * Stops LittleProxy server
     */
    public static void stopServer() {
        Utility.print();
        System.out.println("[.] Stopping server...");
        SERVER.stop();
        System.out.println("[.] Server stopped");
        Utility.print();
        RUNNING = false;

        frame.update();
    }

    /**
     * Save data and goodbye
     */
    public static void stop() {
        stopServer();
        try {
            saveUserLists();
        } catch (IOException ex) {
            Utility.print("[x] Failed to save data: " + ex.getMessage());
            ex.printStackTrace();
            Utility.print();
        }
        GOVERNMENT_BLACKLIST.clear();
        USER_BLACKLIST.clear();
        USER_WHITELIST.clear();
        ISP_STUB_LIST.clear();
    }

    /**
     * Retrieve is LittleProxy server is running
     *
     * @return true if it is or false if it isn't
     */
    public static boolean isRunning() {
        return RUNNING;
    }

    /*
    Journal block
     */

    /**
     * Adds website address to journal
     *
     * @param address - website address
     */
    public static void addToJournal(String address) {
        JOURNAL.put(address, JOURNAL_DATE_FORMAT.format(new Date()));
    }

    /**
     * Retrieves the journal
     *
     * @return journal
     */
    public static Map<String, String> getJournal() {
        return JOURNAL;
    }

    /**
     * Clears the journal
     */
    public static void clearJournal() {
        JOURNAL.clear();
    }

    /*
    Government blacklist block
     */

    /**
     * Retrieves the government blacklist
     *
     * @return government blacklist
     */
    public static Set<String> getGovernmentBlacklist() {
        return GOVERNMENT_BLACKLIST;
    }

    /**
     * Determine if 302-redirect location is ISP (Internet Service Provider) stub
     *
     * @param address - redirect location
     * @return true if it's ISP stub or false if it isn't
     */
    public static boolean isIspStub(String address) {
        String host;
        if(address.contains("/")) {
            host = address.substring(0, address.indexOf("/"));
        } else {
            host = address;
        }
        host = URLUtility.clearHost(host).toLowerCase();
        return ISP_STUB_LIST.contains(host);
    }

    /**
     * Retrieves is the website blocked by the government
     *
     * @param address - website address
     * @return is address blocked by the government
     */
    public static boolean isBlockedByGovernment(String address) {
        return URLUtility.checkIsHostContainsInList(address.toLowerCase(), GOVERNMENT_BLACKLIST);
        //return GOVERNMENT_BLACKLIST.contains(address.toLowerCase());
    }

    /*
    User lists block
     */

    /**
     * Writes user black and whitelist to data store
     *
     * @throws IOException - write failure
     * @see DataStore
     */
    public static void saveUserLists() throws IOException {
        new DataStore(DataStore.USER_BLACKLIST).write(new ArrayList<String>(USER_BLACKLIST));
        new DataStore(DataStore.USER_WHITELIST).write(new ArrayList<String>(USER_WHITELIST));
    }

    /**
     * Refills user list frames
     */
    public static void updateUserListFrames() {
        for (UserListFrame frame : USER_FRAMES) {
            frame.refill();
        }
    }

    /*
    Blacklist
     */

    /**
     * Adds website to the user blacklist
     * and removes from the user whitelist if it's contains in it
     *
     * @param address - website address
     * @return true if address doesn't already contains in the user blacklist or false if it is
     */
    public static boolean addToUserBlacklist(String address) {
        address = address.toLowerCase();
        if(USER_BLACKLIST.contains(address)) {
            return false;
        }
        USER_WHITELIST.remove(address);
        USER_BLACKLIST.add(address);
        updateUserListFrames();
        Utility.print("\n[@] Blacklisted: '%s'\n", address);
        return true;
    }

    /**
     * Retrieve if user blocked website
     *
     * @param address - website address
     * @return true if user blocked website or false if he isn't
     */
    public static boolean isUserBlacklisted(String address) {
        return URLUtility.checkIsHostContainsInList(address.toLowerCase(), USER_BLACKLIST);
        //return USER_BLACKLIST.contains(address.toLowerCase());
    }

    /**
     * Removes website from the user blacklist
     *
     * @param address - website address
     * @return true if address contained in the user blacklist (and removed) or false if it isn't
     */
    public static boolean removeFromUserBlacklist(String address) {
        address = address.toLowerCase();
        if(!USER_BLACKLIST.contains(address)) {
            return false;
        }
        USER_BLACKLIST.remove(address);
        updateUserListFrames();
        Utility.print("\n[@] Removed from the blacklist: '%s'\n", address);
        return true;
    }

    /**
     * Retrieves the user blacklist
     *
     * @return the user blacklist
     */
    public static Set<String> getUserBlacklist() {
        return USER_BLACKLIST;
    }

    /*
    Whitelist
     */

    /**
     * Adds website to the user whitelist
     * and removes from the user blocklist if it's contains in it
     *
     * @param address - website address
     * @return true if address doesn't already contains in the user whitelist or false if it is
     */
    public static boolean addToUserWhitelist(String address) {
        address = address.toLowerCase();
        if(USER_WHITELIST.contains(address)) {
            return false;
        }
        USER_BLACKLIST.remove(address);
        USER_WHITELIST.add(address);
        updateUserListFrames();
        Utility.print("\n[@] Whitelisted: '%s'\n", address);
        return true;
    }

    /**
     * Retrieve if user whitelisted website
     *
     * @param address - website address
     * @return true if user whitelisted website or false if he isn't
     */
    public static boolean isUserWhitelisted(String address) {
        return URLUtility.checkIsHostContainsInList(address.toLowerCase(), USER_WHITELIST);
        //return USER_WHITELIST.contains(address.toLowerCase());
    }

    /**
     * Removes website from the user whitelist
     *
     * @param address - website address
     * @return true if address contained in the user whitelist (and removed) or false if it isn't
     */
    public static boolean removeFromUserWhitelist(String address) {
        address = address.toLowerCase();
        if(!USER_WHITELIST.contains(address)) {
            return false;
        }
        USER_WHITELIST.remove(address);
        updateUserListFrames();
        Utility.print("\n[@] Removed from the whitelist: '%s'\n", address);
        return true;
    }

    /**
     * Retrieves the user whitelist
     *
     * @return the user whitelist
     */
    public static Set<String> getUserWhitelist() {
        return USER_WHITELIST;
    }
}