// yacySeedDB.java 
// -------------------------------------
// (C) by Michael Peter Christen; mc@yacy.net
// first published on http://www.anomic.de
// Frankfurt, Germany, 2004, 2005
//
// $LastChangedDate$
// $LastChangedRevision$
// $LastChangedBy$
//
// This program is free software; you can redistribute it and/or modify
// it under the terms of the GNU General Public License as published by
// the Free Software Foundation; either version 2 of the License, or
// (at your option) any later version.
//
// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
// GNU General Public License for more details.
//
// You should have received a copy of the GNU General Public License
// along with this program; if not, write to the Free Software
// Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA

package de.anomic.yacy;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.lang.ref.SoftReference;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.Map;
import java.util.TreeMap;

import net.yacy.kelondro.blob.Heap;
import net.yacy.kelondro.blob.MapDataMining;
import net.yacy.kelondro.data.meta.DigestURI;
import net.yacy.kelondro.data.word.Word;
import net.yacy.kelondro.logging.Log;
import net.yacy.kelondro.order.Base64Order;
import net.yacy.kelondro.util.FileUtils;
import net.yacy.kelondro.util.kelondroException;

import de.anomic.crawler.retrieval.HTTPLoader;
import de.anomic.http.client.Client;
import de.anomic.http.metadata.HeaderFramework;
import de.anomic.http.metadata.RequestHeader;
import de.anomic.http.metadata.ResponseContainer;
import de.anomic.http.server.HTTPDemon;
import de.anomic.http.server.AlternativeDomainNames;
import de.anomic.search.Switchboard;
import de.anomic.server.serverCore;
import de.anomic.server.serverSwitch;
import de.anomic.yacy.dht.PartitionScheme;
import de.anomic.yacy.dht.VerticalWordPartitionScheme;

public final class yacySeedDB implements AlternativeDomainNames {
  
    // global statics

    public static final int dhtActivityMagic = 32;
    
    /**
     * <p><code>public static final String <strong>DBFILE_OWN_SEED</strong> = "mySeed.txt"</code></p>
     * <p>Name of the file containing the database holding this peer's seed</p>
     */
    public static final String DBFILE_OWN_SEED = "mySeed.txt";
    
    public static final String[]      sortFields = new String[] {yacySeed.LCOUNT, yacySeed.ICOUNT, yacySeed.UPTIME, yacySeed.VERSION, yacySeed.LASTSEEN};
    public static final String[]   longaccFields = new String[] {yacySeed.LCOUNT, yacySeed.ICOUNT, yacySeed.ISPEED};
    public static final String[] doubleaccFields = new String[] {yacySeed.RSPEED};
    
    // class objects
    protected File seedActiveDBFile, seedPassiveDBFile, seedPotentialDBFile;
    protected File myOwnSeedFile;
    protected MapDataMining seedActiveDB, seedPassiveDB, seedPotentialDB;
    
    public int lastSeedUpload_seedDBSize = 0;
    public long lastSeedUpload_timeStamp = System.currentTimeMillis();
    public String lastSeedUpload_myIP = "";

    public  yacyPeerActions peerActions;
    public  yacyNewsPool newsPool;
    
    private int netRedundancy;
    public  PartitionScheme scheme;
    
    private yacySeed mySeed; // my own seed
    
    private final Hashtable<String, String> nameLookupCache; // a name-to-hash relation
    private final Hashtable<InetAddress, SoftReference<yacySeed>> ipLookupCache;
    
    public yacySeedDB(
            final File networkRoot,
            final String seedActiveDBFileName,
            final String seedPassiveDBFileName,
            final String seedPotentialDBFileName,
            final File myOwnSeedFile, 
            final int redundancy,
            final int partitionExponent,
            final boolean useTailCache,
            final boolean exceed134217727) {
        this.seedActiveDBFile = new File(networkRoot, seedActiveDBFileName);
        this.seedPassiveDBFile = new File(networkRoot, seedPassiveDBFileName);
        this.seedPotentialDBFile = new File(networkRoot, seedPotentialDBFileName);
        this.mySeed = null; // my own seed
        this.myOwnSeedFile = myOwnSeedFile;
        this.netRedundancy = redundancy;
        this.scheme = new VerticalWordPartitionScheme(partitionExponent);
        
        // set up seed database
        seedActiveDB = openSeedTable(seedActiveDBFile);
        seedPassiveDB = openSeedTable(seedPassiveDBFile);
        seedPotentialDB = openSeedTable(seedPotentialDBFile);
        
        // start our virtual DNS service for yacy peers with empty cache
        nameLookupCache = new Hashtable<String, String>();
        
        // cache for reverse name lookup
        ipLookupCache = new Hashtable<InetAddress, SoftReference<yacySeed>>();
        
        // check if we are in the seedCaches: this can happen if someone else published our seed
        removeMySeed();
        
        lastSeedUpload_seedDBSize = sizeConnected();

        // tell the httpdProxy how to find this table as address resolver
        HTTPDemon.setAlternativeResolver(this);
        
        // create or init news database
        this.newsPool = new yacyNewsPool(networkRoot, useTailCache, exceed134217727);
        
        // deploy peer actions
        this.peerActions = new yacyPeerActions(this, newsPool);
    }
    
    private synchronized void initMySeed() {
        if (this.mySeed != null) return;
        
        // create or init own seed
        if (myOwnSeedFile.length() > 0) try {
            // load existing identity
            mySeed = yacySeed.load(myOwnSeedFile);
            if(mySeed == null) throw new IOException("current seed is null");
        } catch (final IOException e) {
            // create new identity
            Log.logSevere("SEEDDB", "could not load stored mySeed.txt from " + myOwnSeedFile.toString() + ": " + e.getMessage() + ". creating new seed.", e);
            mySeed = yacySeed.genLocalSeed(this);
            try {
                mySeed.save(myOwnSeedFile);
            } catch (final IOException ee) {
                Log.logSevere("SEEDDB", "error saving mySeed.txt (1) to " + myOwnSeedFile.toString() + ": " + ee.getMessage(), ee);
                ee.printStackTrace();
                System.exit(-1);
            }
        } else {
            // create new identity
            Log.logInfo("SEEDDB", "could not find stored mySeed.txt at " + myOwnSeedFile.toString() + ": " + ". creating new seed.");
            mySeed = yacySeed.genLocalSeed(this);
            try {
                mySeed.save(myOwnSeedFile);
            } catch (final IOException ee) {
                Log.logSevere("SEEDDB", "error saving mySeed.txt (2) to " + myOwnSeedFile.toString() + ": " + ee.getMessage(), ee);
                ee.printStackTrace();
                System.exit(-1);
            }
        }
        
        mySeed.setIP("");       // we delete the old information to see what we have now
        mySeed.put(yacySeed.PEERTYPE, yacySeed.PEERTYPE_VIRGIN); // markup startup condition
    }

    public int redundancy() {
        if (this.mySeed.isJunior()) return 1;
        return this.netRedundancy;
    }
    
    public boolean mySeedIsDefined() {
        return this.mySeed != null;
    }
    
    public yacySeed mySeed() {
        if (this.mySeed == null) {
            if (this.sizeConnected() == 0) try {Thread.sleep(5000);} catch (final InterruptedException e) {} // wait for init
            initMySeed();
        }
        return this.mySeed;
    }
    
    public String myAlternativeAddress() {
        return mySeed().getName() + ".yacy";
    }
    
    public String myIP() {
        return mySeed().getIP();
    }
    
    public int myPort() {
        return mySeed().getPort();
    }
    
    public String myName() {
        return mySeed.getName();
    }
    
    public String myID() {
        return mySeed.hash;
    }
    
    public synchronized void removeMySeed() {
        if ((seedActiveDB.size() == 0) && (seedPassiveDB.size() == 0) && (seedPotentialDB.size() == 0)) return; // avoid that the own seed is initialized too early
        if (this.mySeed == null) initMySeed();
        try {
            seedActiveDB.remove(mySeed.hash);
            seedPassiveDB.remove(mySeed.hash);
            seedPotentialDB.remove(mySeed.hash);
        } catch (final IOException e) { Log.logWarning("yacySeedDB", "could not remove hash ("+ e.getClass() +"): "+ e.getMessage()); }
    }
    
    public void saveMySeed() {
        try {
          this.mySeed().save(myOwnSeedFile);
        } catch (final IOException e) { Log.logWarning("yacySeedDB", "could not save mySeed '"+ myOwnSeedFile +"': "+ e.getMessage()); }
    }
    
    public boolean noDHTActivity() {
        // for small networks, we don't perform DHT transmissions, because it is possible to search over all peers
        return this.sizeConnected() <= dhtActivityMagic;
    }
    
    private synchronized MapDataMining openSeedTable(final File seedDBFile) {
        final File parentDir = new File(seedDBFile.getParent());  
        if (!parentDir.exists()) {
			if(!parentDir.mkdirs())
				Log.logWarning("yacySeedDB", "could not create directories for "+ seedDBFile.getParent());
		}
        try {
            return new MapDataMining(new Heap(seedDBFile, Word.commonHashLength, Base64Order.enhancedCoder, 1024 * 512), 500, sortFields, longaccFields, doubleaccFields, null, this);
        } catch (final Exception e) {
            // try again
            FileUtils.deletedelete(seedDBFile);
            try {
                return new MapDataMining(new Heap(seedDBFile, Word.commonHashLength, Base64Order.enhancedCoder, 1024 * 512), 500, sortFields, longaccFields, doubleaccFields, null, this);
            } catch (IOException e1) {
                e1.printStackTrace();
                System.exit(-1);
                return null;
            }
        }
    }
    
    protected synchronized MapDataMining resetSeedTable(MapDataMining seedDB, final File seedDBFile) {
        // this is an emergency function that should only be used if any problem with the
        // seed.db is detected
        yacyCore.log.logWarning("seed-db " + seedDBFile.toString() + " reset (on-the-fly)");
        seedDB.close();
        FileUtils.deletedelete(seedDBFile);
        if (seedDBFile.exists())
        	Log.logWarning("yacySeedDB", "could not delete file "+ seedDBFile);
        // create new seed database
        seedDB = openSeedTable(seedDBFile);
        return seedDB;
    }
    
    public synchronized void resetActiveTable() { seedActiveDB = resetSeedTable(seedActiveDB, seedActiveDBFile); }
    public synchronized void resetPassiveTable() { seedPassiveDB = resetSeedTable(seedPassiveDB, seedPassiveDBFile); }
    public synchronized void resetPotentialTable() { seedPotentialDB = resetSeedTable(seedPotentialDB, seedPotentialDBFile); }
    
    public void close() {
        if (seedActiveDB != null) seedActiveDB.close();
        if (seedPassiveDB != null) seedPassiveDB.close();
        if (seedPotentialDB != null) seedPotentialDB.close();
        newsPool.close();
        peerActions.close();
    }
    
    public Iterator<yacySeed> seedsSortedConnected(final boolean up, final String field) {
        // enumerates seed-type objects: all seeds sequentially ordered by field
        return new seedEnum(up, field, seedActiveDB);
    }
    
    public Iterator<yacySeed> seedsSortedDisconnected(final boolean up, final String field) {
        // enumerates seed-type objects: all seeds sequentially ordered by field
        return new seedEnum(up, field, seedPassiveDB);
    }
    
    public Iterator<yacySeed> seedsSortedPotential(final boolean up, final String field) {
        // enumerates seed-type objects: all seeds sequentially ordered by field
        return new seedEnum(up, field, seedPotentialDB);
    }
    
    public TreeMap<byte[], String> /* peer-b64-hashes/ipport */ clusterHashes(final String clusterdefinition) {
    	// collects seeds according to cluster definition string, which consists of
    	// comma-separated .yacy or .yacyh-domains
    	// the domain may be extended by an alternative address specification of the form
    	// <ip> or <ip>:<port>. The port must be identical to the port specified in the peer seed,
    	// therefore it is optional. The address specification is separated by a '='; the complete
    	// address has therefore the form
    	// address    ::= (<peername>'.yacy'|<peerhexhash>'.yacyh'){'='<ip>{':'<port}}
    	// clusterdef ::= {address}{','address}*
    	final String[] addresses = (clusterdefinition.length() == 0) ? new String[0] : clusterdefinition.split(",");
    	final TreeMap<byte[], String> clustermap = new TreeMap<byte[], String>(Base64Order.enhancedCoder);
    	yacySeed seed;
    	String hash, yacydom, ipport;
    	int p;
    	for (int i = 0; i < addresses.length; i++) {
    		p = addresses[i].indexOf('=');
    		if (p >= 0) {
    			yacydom = addresses[i].substring(0, p);
    			ipport  = addresses[i].substring(p + 1);
    		} else {
    			yacydom = addresses[i];
    			ipport  = null;
    		}
    		if (yacydom.endsWith(".yacyh")) {
    			// find a peer with its hexhash
    			hash = yacySeed.hexHash2b64Hash(yacydom.substring(0, yacydom.length() - 6));
    			seed = get(hash);
    			if (seed == null) {
    				yacyCore.log.logWarning("cluster peer '" + yacydom + "' was not found.");
    			} else {
    				clustermap.put(hash.getBytes(), ipport);
    			}
    		} else if (yacydom.endsWith(".yacy")) {
    			// find a peer with its name
    			seed = lookupByName(yacydom.substring(0, yacydom.length() - 5));
    			if (seed == null) {
    				yacyCore.log.logWarning("cluster peer '" + yacydom + "' was not found.");
    			} else {
    				clustermap.put(seed.hash.getBytes(), ipport);
    			}
    		} else {
    			yacyCore.log.logWarning("cluster peer '" + addresses[i] + "' has wrong syntax. the name must end with .yacy or .yacyh");
    		}
    	}
    	return clustermap;
    }
    
    public Iterator<yacySeed> seedsConnected(final boolean up, final boolean rot, final byte[] firstHash, final float minVersion) {
        // enumerates seed-type objects: all seeds sequentially without order
        return new seedEnum(up, rot, (firstHash == null) ? null : firstHash, null, seedActiveDB, minVersion);
    }
    
    public Iterator<yacySeed> seedsDisconnected(final boolean up, final boolean rot, final byte[] firstHash, final float minVersion) {
        // enumerates seed-type objects: all seeds sequentially without order
        return new seedEnum(up, rot, (firstHash == null) ? null : firstHash, null, seedPassiveDB, minVersion);
    }
    
    public Iterator<yacySeed> seedsPotential(final boolean up, final boolean rot, final byte[] firstHash, final float minVersion) {
        // enumerates seed-type objects: all seeds sequentially without order
        return new seedEnum(up, rot, (firstHash == null) ? null : firstHash, null, seedPotentialDB, minVersion);
    }
    
    public yacySeed anySeedVersion(final float minVersion) {
        // return just any seed that has a specific minimum version number
        final Iterator<yacySeed> e = seedsConnected(true, true, yacySeed.randomHash(), minVersion);
        return e.next();
    }

    public int sizeConnected() {
        return seedActiveDB.size();
    }
    
    public int sizeDisconnected() {
        return seedPassiveDB.size();
    }
    
    public int sizePotential() {
        return seedPotentialDB.size();
    }
    
    public long countActiveURL() { return seedActiveDB.getLongAcc(yacySeed.LCOUNT); }
    public long countActiveRWI() { return seedActiveDB.getLongAcc(yacySeed.ICOUNT); }
    public long countActivePPM() { return seedActiveDB.getLongAcc(yacySeed.ISPEED); }
    public double countActiveQPM() { return seedActiveDB.getDoubleAcc(yacySeed.RSPEED); }
    public long countPassiveURL() { return seedPassiveDB.getLongAcc(yacySeed.LCOUNT); }
    public long countPassiveRWI() { return seedPassiveDB.getLongAcc(yacySeed.ICOUNT); }
    public long countPotentialURL() { return seedPotentialDB.getLongAcc(yacySeed.LCOUNT); }
    public long countPotentialRWI() { return seedPotentialDB.getLongAcc(yacySeed.ICOUNT); }

    public synchronized void addConnected(final yacySeed seed) {
        if (seed.isProper(false) != null) return;
        //seed.put(yacySeed.LASTSEEN, yacyCore.shortFormatter.format(new Date(yacyCore.universalTime())));
        try {
            nameLookupCache.put(seed.getName(), seed.hash);
            final Map<String, String> seedPropMap = seed.getMap();
            synchronized (seedPropMap) {
                seedActiveDB.put(seed.hash, seedPropMap);
            }
            seedPassiveDB.remove(seed.hash);
            seedPotentialDB.remove(seed.hash);
        } catch (final IOException e) {
            yacyCore.log.logSevere("ERROR add: seed.db corrupt (" + e.getMessage() + "); resetting seed.db", e);
            resetActiveTable();
        } catch (final kelondroException e) {
            yacyCore.log.logSevere("ERROR add: seed.db corrupt (" + e.getMessage() + "); resetting seed.db", e);
            resetActiveTable();
        } catch (final IllegalArgumentException e) {
            yacyCore.log.logSevere("ERROR add: seed.db corrupt (" + e.getMessage() + "); resetting seed.db", e);
            resetActiveTable();
        }
    }

    public synchronized void addDisconnected(final yacySeed seed) {
        if (seed.isProper(false) != null) return;
        try {
            nameLookupCache.remove(seed.getName());
            seedActiveDB.remove(seed.hash);
            seedPotentialDB.remove(seed.hash);
        } catch (final Exception e) { Log.logWarning("yacySeedDB", "could not remove hash ("+ e.getClass() +"): "+ e.getMessage()); }
        //seed.put(yacySeed.LASTSEEN, yacyCore.shortFormatter.format(new Date(yacyCore.universalTime())));
        try {
            final Map<String, String> seedPropMap = seed.getMap();
            synchronized (seedPropMap) {
                seedPassiveDB.put(seed.hash, seedPropMap);
            }
        } catch (final IOException e) {
            yacyCore.log.logSevere("ERROR add: seed.db corrupt (" + e.getMessage() + "); resetting seed.db", e);
            resetPassiveTable();
        } catch (final kelondroException e) {
            yacyCore.log.logSevere("ERROR add: seed.db corrupt (" + e.getMessage() + "); resetting seed.db", e);
            resetPassiveTable();
        } catch (final IllegalArgumentException e) {
            yacyCore.log.logSevere("ERROR add: seed.db corrupt (" + e.getMessage() + "); resetting seed.db", e);
            resetPassiveTable();
        }
    }

    public synchronized void addPotential(final yacySeed seed) {
        if (seed.isProper(false) != null) return;
        try {
            nameLookupCache.remove(seed.getName());
            seedActiveDB.remove(seed.hash);
            seedPassiveDB.remove(seed.hash);
        } catch (final Exception e) { Log.logWarning("yacySeedDB", "could not remove hash ("+ e.getClass() +"): "+ e.getMessage()); }
        //seed.put(yacySeed.LASTSEEN, yacyCore.shortFormatter.format(new Date(yacyCore.universalTime())));
        try {
            final Map<String, String> seedPropMap = seed.getMap();
            synchronized (seedPropMap) {
                seedPotentialDB.put(seed.hash, seedPropMap);
            }
        } catch (final IOException e) {
            yacyCore.log.logSevere("ERROR add: seed.db corrupt (" + e.getMessage() + "); resetting seed.db", e);
            resetPotentialTable();
        } catch (final kelondroException e) {
            yacyCore.log.logSevere("ERROR add: seed.db corrupt (" + e.getMessage() + "); resetting seed.db", e);
            resetPotentialTable();
        } catch (final IllegalArgumentException e) {
            yacyCore.log.logSevere("ERROR add: seed.db corrupt (" + e.getMessage() + "); resetting seed.db", e);
            resetPotentialTable();
        }
    }

    public synchronized void removeDisconnected(final String peerHash) {
    	if(peerHash == null) return;
    	try {
			seedPassiveDB.remove(peerHash);
		} catch (final IOException e) { Log.logWarning("yacySeedDB", "could not remove hash ("+ e.getClass() +"): "+ e.getMessage()); }
    }
    
    public synchronized void removePotential(final String peerHash) {
    	if(peerHash == null) return;
    	try {
			seedPotentialDB.remove(peerHash);
		} catch (final IOException e) { Log.logWarning("yacySeedDB", "could not remove hash ("+ e.getClass() +"): "+ e.getMessage()); }
    }
        
    public boolean hasConnected(final String hash) {
    try {
        return seedActiveDB.has(hash);
    } catch (final IOException e) {
        return false;
    }
    }

    public boolean hasDisconnected(final String hash) {
    try {
        return seedPassiveDB.has(hash);
    } catch (final IOException e) {
        return false;
    }
    }
 
    public boolean hasPotential(final String hash) {
    try {
        return seedPotentialDB.has(hash);
    } catch (final IOException e) {
        return false;
    }
    }
        
    private yacySeed get(final String hash, final MapDataMining database) {
        if (hash == null) return null;
        if ((this.mySeed != null) && (hash.equals(mySeed.hash))) return mySeed;
        Map<String, String> entry;
        try {
            entry = database.get(hash);
        } catch (final IOException e) {
            entry = null;
        }
        if (entry == null) return null;
        return new yacySeed(hash, entry);
    }
    
    public yacySeed getConnected(final String hash) {
        return get(hash, seedActiveDB);
    }

    public yacySeed getDisconnected(final String hash) {
        return get(hash, seedPassiveDB);
    }
        
    public yacySeed getPotential(final String hash) {
        return get(hash, seedPotentialDB);
    }
    
    public yacySeed get(final String hash) {
        yacySeed seed = getConnected(hash);
        if (seed == null) seed = getDisconnected(hash);
        if (seed == null) seed = getPotential(hash);
        return seed;
    }
    
    public void update(final String hash, final yacySeed seed) {
        if (this.mySeed == null) initMySeed();
        if (hash.equals(mySeed.hash)) {
            mySeed = seed;
            return;
        }
        
        yacySeed s = get(hash, seedActiveDB);
        if (s != null) try { seedActiveDB.put(hash, seed.getMap()); return;} catch (final IOException e) {}
        
        s = get(hash, seedPassiveDB);
        if (s != null) try { seedPassiveDB.put(hash, seed.getMap()); return;} catch (final IOException e) {}
        
        s = get(hash, seedPotentialDB);
        if (s != null) try { seedPotentialDB.put(hash, seed.getMap()); return;} catch (final IOException e) {}
    }
    
    public yacySeed lookupByName(String peerName) {
        // reads a seed by searching by name
        if (peerName.endsWith(".yacy")) peerName = peerName.substring(0, peerName.length() - 5);

        // local peer?
        if (peerName.equals("localpeer")) {
            if (this.mySeed == null) initMySeed();
            return mySeed;
        }
        
        // then try to use the cache
        String seedhash = nameLookupCache.get(peerName);
        yacySeed seed;
        if (seedhash != null) {
        	seed = this.get(seedhash);
        	if (seed != null) return seed;
        }
        
        // enumerate the cache and simultanous insert values
        String name;
    	for (int table = 0; table < 2; table++) {
            final Iterator<yacySeed> e = (table == 0) ? seedsConnected(true, false, null, (float) 0.0) : seedsDisconnected(true, false, null, (float) 0.0);
        	while (e.hasNext()) {
        		seed = e.next();
        		if (seed != null) {
        			name = seed.getName().toLowerCase();
        			if (seed.isProper(false) == null) nameLookupCache.put(name, seed.hash);
        			if (name.equals(peerName)) return seed;
        		}
        	}
        }
        // check local seed
        if (this.mySeed == null) initMySeed();
        name = mySeed.getName().toLowerCase();
        if (mySeed.isProper(false) == null) nameLookupCache.put(name, mySeed.hash);
        if (name.equals(peerName)) return mySeed;
        // nothing found
        return null;
    }
    
    public yacySeed lookupByIP(
            final InetAddress peerIP, 
            final boolean lookupConnected, 
            final boolean lookupDisconnected,
            final boolean lookupPotential
    ) {
        
        if (peerIP == null) return null;
        yacySeed seed = null;        
        
        // local peer?
        if (HTTPDemon.isThisHostIP(peerIP)) {
            if (this.mySeed == null) initMySeed();
            return mySeed;
        }
        
        // then try to use the cache
        final SoftReference<yacySeed> ref = ipLookupCache.get(peerIP);
        if (ref != null) {        
            seed = ref.get();
            if (seed != null) return seed;
        }

        int pos = -1;
        String addressStr = null;
        InetAddress seedIPAddress = null;        
        final HashSet<String> badPeerHashes = new HashSet<String>();
        
        if (lookupConnected) {
            // enumerate the cache and simultanous insert values
            final Iterator<yacySeed> e = seedsConnected(true, false, null, (float) 0.0);
            while (e.hasNext()) {
                try {
                    seed = e.next();
                    if (seed != null) {
                        addressStr = seed.getPublicAddress();
                        if (addressStr == null) {
                        	Log.logWarning("YACY","lookupByIP/Connected: address of seed " + seed.getName() + "/" + seed.hash + " is null.");
                        	badPeerHashes.add(seed.hash);
                        	continue; 
                        }
                        if ((pos = addressStr.indexOf(":"))!= -1) {
                            addressStr = addressStr.substring(0,pos);
                        }
                        seedIPAddress = InetAddress.getByName(addressStr);
                        if (seed.isProper(false) == null) ipLookupCache.put(seedIPAddress, new SoftReference<yacySeed>(seed));
                        if (seedIPAddress.equals(peerIP)) return seed;
                    }
                } catch (final UnknownHostException ex) {}
            }
            // delete bad peers
            final Iterator<String> i = badPeerHashes.iterator();
            while (i.hasNext()) try {seedActiveDB.remove(i.next());} catch (final IOException e1) {e1.printStackTrace();}
            badPeerHashes.clear();
        }
        
        if (lookupDisconnected) {
            // enumerate the cache and simultanous insert values
            final Iterator<yacySeed>e = seedsDisconnected(true, false, null, (float) 0.0);

            while (e.hasNext()) {
                try {
                    seed = e.next();
                    if (seed != null) {
                        addressStr = seed.getPublicAddress();
                        if (addressStr == null) {
                            Log.logWarning("YACY","lookupByIPDisconnected: address of seed " + seed.getName() + "/" + seed.hash + " is null.");
                            badPeerHashes.add(seed.hash);
                            continue;
                        }
                        if ((pos = addressStr.indexOf(":"))!= -1) {
                            addressStr = addressStr.substring(0,pos);
                        }
                        seedIPAddress = InetAddress.getByName(addressStr);
                        if (seed.isProper(false) == null) ipLookupCache.put(seedIPAddress, new SoftReference<yacySeed>(seed));
                        if (seedIPAddress.equals(peerIP)) return seed;
                    }
                } catch (final UnknownHostException ex) {}
            }
            // delete bad peers
            final Iterator<String> i = badPeerHashes.iterator();
            while (i.hasNext()) try {seedActiveDB.remove(i.next());} catch (final IOException e1) {e1.printStackTrace();}
            badPeerHashes.clear();
        }
        
        if (lookupPotential) {
            // enumerate the cache and simultanous insert values
            final Iterator<yacySeed> e = seedsPotential(true, false, null, (float) 0.0);

            while (e.hasNext()) {
                try {
                    seed = e.next();
                    if ((seed != null) && ((addressStr = seed.getPublicAddress()) != null)) {
                        if ((pos = addressStr.indexOf(":"))!= -1) {
                            addressStr = addressStr.substring(0,pos);
                        }
                        seedIPAddress = InetAddress.getByName(addressStr);
                        if (seed.isProper(false) == null) ipLookupCache.put(seedIPAddress, new SoftReference<yacySeed>(seed));
                        if (seedIPAddress.equals(peerIP)) return seed;
                    }
                } catch (final UnknownHostException ex) {}
            }
        }
        
        try {
            // check local seed
            if (this.mySeed == null) return null;
            addressStr = mySeed.getPublicAddress();
            if (addressStr == null) return null;
            if ((pos = addressStr.indexOf(":"))!= -1) {
                addressStr = addressStr.substring(0,pos);
            }
            seedIPAddress = InetAddress.getByName(addressStr);
            if (mySeed.isProper(false) == null) ipLookupCache.put(seedIPAddress,  new SoftReference<yacySeed>(mySeed));
            if (seedIPAddress.equals(peerIP)) return mySeed;
            // nothing found
            return null;
        } catch (final UnknownHostException e2) {
            return null;
        }
    }
    
    public ArrayList<String> storeCache(final File seedFile) throws IOException {
    	return storeCache(seedFile, false);
    }

    private ArrayList<String> storeCache(final File seedFile, final boolean addMySeed) throws IOException {
        PrintWriter pw = null;
        final ArrayList<String> v = new ArrayList<String>(seedActiveDB.size() + 1);
        try {
            
            pw = new PrintWriter(new BufferedWriter(new FileWriter(seedFile)));
            
            // store own seed
            String line;
            if (this.mySeed == null) initMySeed();
            if (addMySeed) {
                line = mySeed.genSeedStr(null);
                v.add(line);
                pw.print(line + serverCore.CRLF_STRING);
            }
            
            // store other seeds
            yacySeed ys;
            final Iterator<yacySeed> se = seedsConnected(true, false, null, (float) 0.0);
            while (se.hasNext()) {
                ys = se.next();
                if (ys != null) {
                    line = ys.genSeedStr(null);
                    v.add(line);
                    pw.print(line + serverCore.CRLF_STRING);
                }
            }
            pw.flush();
        } finally {
            if (pw != null) try { pw.close(); } catch (final Exception e) {}
        }
        return v;
    }

    public String uploadCache(final yacySeedUploader uploader, 
            final serverSwitch sb,
            final yacySeedDB seedDB,
            final DigestURI seedURL) throws Exception {
        
        // upload a seed file, if possible
        if (seedURL == null) throw new NullPointerException("UPLOAD - Error: URL not given");
        
        String log = null; 
        File seedFile = null;
        try {            
            // create a seed file which for uploading ...    
            seedFile = File.createTempFile("seedFile",".txt", seedDB.myOwnSeedFile.getParentFile());
            seedFile.deleteOnExit();
            if (Log.isFine("YACY")) Log.logFine("YACY", "SaveSeedList: Storing seedlist into tempfile " + seedFile.toString());
            final ArrayList<String> uv = storeCache(seedFile, true);            
            
            // uploading the seed file
            if (Log.isFine("YACY")) Log.logFine("YACY", "SaveSeedList: Trying to upload seed-file, " + seedFile.length() + " bytes, " + uv.size() + " entries.");
            log = uploader.uploadSeedFile(sb,seedDB,seedFile);
            
            // test download
            if (Log.isFine("YACY")) Log.logFine("YACY", "SaveSeedList: Trying to download seed-file '" + seedURL + "'.");
            final Iterator<String> check = downloadSeedFile(seedURL);
            
            // Comparing if local copy and uploaded copy are equal
            final String errorMsg = checkCache(uv, check);
            if (errorMsg == null)
                log = log + "UPLOAD CHECK - Success: the result vectors are equal" + serverCore.CRLF_STRING;
            else {
                throw new Exception("UPLOAD CHECK - Error: the result vector is different. " + errorMsg + serverCore.CRLF_STRING);
            }
        } finally {
            if (seedFile != null)
				try {
				    FileUtils.deletedelete(seedFile);
				} catch (final Exception e) {
					/* ignore this */
				}
        }
        
        return log;
    }
    
    private Iterator<String> downloadSeedFile(final DigestURI seedURL) throws IOException {
        // Configure http headers
        final RequestHeader reqHeader = new RequestHeader();
        reqHeader.put(HeaderFramework.PRAGMA, "no-cache");
        reqHeader.put(HeaderFramework.CACHE_CONTROL, "no-cache"); // httpc uses HTTP/1.0 is this necessary?
        reqHeader.put(HeaderFramework.USER_AGENT, HTTPLoader.yacyUserAgent);
        
        // init http-client
        final Client client = new Client(10000, reqHeader);
        byte[] content = null;
        ResponseContainer res = null;
        try {
            // send request
            res = client.GET(seedURL.toString());
            
            // check response code
            if (res.getStatusCode() != 200) {
            	throw new IOException("Server returned status: " + res.getStatusLine());
            }
            
            // read byte array
                content = res.getData();
        } finally {
            if(res != null) {
                res.closeStream();
            }
        }
            
        try {
            // uncompress it if it is gzipped
            content = FileUtils.uncompressGZipArray(content);

            // convert it into an array
            return FileUtils.strings(content);
        } catch (final Exception e) {
        	throw new IOException("Unable to download seed file '" + seedURL + "'. " + e.getMessage());
        }
    }

    private String checkCache(final ArrayList<String> uv, final Iterator<String> check) {                
        if ((check == null) || (uv == null)) {
            if (Log.isFine("YACY")) Log.logFine("YACY", "SaveSeedList: Local and uploades seed-list are different");
            return "Entry count is different: uv.size() = " + ((uv == null) ? "null" : Integer.toString(uv.size()));
        }
        	
        if (Log.isFine("YACY")) Log.logFine("YACY", "SaveSeedList: Comparing local and uploades seed-list entries ...");
        int i = 0;
        while (check.hasNext() && i < uv.size()) {
        	if (!((uv.get(i)).equals(check.next()))) return "Element at position " + i + " is different.";
        	i++;
        }
        
        // no difference found
        return null;
    }

    /**
     * resolve a yacy address
     */
    public String resolve(String host) {
        yacySeed seed;
        int p;
        String subdom = null;
        if (host.endsWith(".yacyh")) {
            // this is not functional at the moment
            // caused by lowecasing of hashes at the browser client
            p = host.indexOf(".");
            if ((p > 0) && (p != (host.length() - 6))) {
                subdom = host.substring(0, p);
                host = host.substring(p + 1);
            }
            // check if we have a b64-hash or a hex-hash
            String hash = host.substring(0, host.length() - 6);
            if (hash.length() > Word.commonHashLength) {
                // this is probably a hex-hash
                hash = yacySeed.hexHash2b64Hash(hash);
            }
            // check remote seeds
            seed = getConnected(hash); // checks only remote, not local
            // check local seed
            if (seed == null) {
                if (this.mySeed == null) initMySeed();
                if (hash.equals(mySeed.hash))
                    seed = mySeed;
                else return null;
            }
            return seed.getPublicAddress() + ((subdom == null) ? "" : ("/" + subdom));
        } else if (host.endsWith(".yacy")) {
            // identify subdomain
            p = host.indexOf(".");
            if ((p > 0) && (p != (host.length() - 5))) {
                subdom = host.substring(0, p); // no double-dot attack possible, the subdom cannot have ".." in it
                host = host.substring(p + 1); // if ever, the double-dots are here but do not harm
            }
            // identify domain
            final String domain = host.substring(0, host.length() - 5).toLowerCase();
            seed = lookupByName(domain);
            if (seed == null) return null;
            if (this.mySeed == null) initMySeed();
            if ((seed == mySeed) && (!(seed.isOnline()))) {
                // take local ip instead of external
                return Switchboard.getSwitchboard().myPublicIP() + ":8080" + ((subdom == null) ? "" : ("/" + subdom));
            }
            return seed.getPublicAddress() + ((subdom == null) ? "" : ("/" + subdom));
        } else {
            return null;
        }
    }

    class seedEnum implements Iterator<yacySeed> {
        
        MapDataMining.mapIterator it;
        yacySeed nextSeed;
        MapDataMining database;
        float minVersion;
        
        public seedEnum(final boolean up, final boolean rot, final byte[] firstKey, final byte[] secondKey, final MapDataMining database, final float minVersion) {
            this.database = database;
            this.minVersion = minVersion;
            try {
                it = (firstKey == null) ? database.maps(up, rot) : database.maps(up, rot, firstKey, secondKey);
                while (true) {
                    nextSeed = internalNext();
                    if (nextSeed == null) break;
                    if (nextSeed.getVersion() >= this.minVersion) break;
                }
            } catch (final IOException e) {
                e.printStackTrace();
                yacyCore.log.logSevere("ERROR seedLinEnum: seed.db corrupt (" + e.getMessage() + "); resetting seed.db", e);
                if (database == seedActiveDB) seedActiveDB = resetSeedTable(seedActiveDB, seedActiveDBFile);
                if (database == seedPassiveDB) seedPassiveDB = resetSeedTable(seedPassiveDB, seedPassiveDBFile);
                it = null;
            } catch (final kelondroException e) {
                e.printStackTrace();
                yacyCore.log.logSevere("ERROR seedLinEnum: seed.db corrupt (" + e.getMessage() + "); resetting seed.db", e);
                if (database == seedActiveDB) seedActiveDB = resetSeedTable(seedActiveDB, seedActiveDBFile);
                if (database == seedPassiveDB) seedPassiveDB = resetSeedTable(seedPassiveDB, seedPassiveDBFile);
                it = null;
            }
        }
        
        public seedEnum(final boolean up, final String field, final MapDataMining database) {
            this.database = database;
            try {
                it = database.maps(up, field);
                nextSeed = internalNext();
            } catch (final kelondroException e) {
                e.printStackTrace();
                yacyCore.log.logSevere("ERROR seedLinEnum: seed.db corrupt (" + e.getMessage() + "); resetting seed.db", e);
                if (database == seedActiveDB) seedActiveDB = resetSeedTable(seedActiveDB, seedActiveDBFile);
                if (database == seedPassiveDB) seedPassiveDB = resetSeedTable(seedPassiveDB, seedPassiveDBFile);
                if (database == seedPotentialDB) seedPotentialDB = resetSeedTable(seedPotentialDB, seedPotentialDBFile);
                it = null;
            }
        }
        
        public boolean hasNext() {
            return (nextSeed != null);
        }
        
        public yacySeed internalNext() {
            if ((it == null) || (!(it.hasNext()))) return null;
            try {
                while (true) {
                    final Map<String, String> dna = it.next();
                    if (dna == null) return null;
                    final String hash = dna.remove("key");
                    if (hash == null) { continue; } // bad seed
                    return new yacySeed(hash, dna);
                }
            } catch (final Exception e) {
                e.printStackTrace();
                yacyCore.log.logSevere("ERROR internalNext: seed.db corrupt (" + e.getMessage() + "); resetting seed.db", e);
                if (database == seedActiveDB) seedActiveDB = resetSeedTable(seedActiveDB, seedActiveDBFile);
                if (database == seedPassiveDB) seedPassiveDB = resetSeedTable(seedPassiveDB, seedPassiveDBFile);
                if (database == seedPotentialDB) seedPotentialDB = resetSeedTable(seedPotentialDB, seedPotentialDBFile);
                return null;
            }
        }
        
        public yacySeed next() {
            final yacySeed seed = nextSeed;
            try {while (true) {
                nextSeed = internalNext();
                if (nextSeed == null) break;
                if (nextSeed.getVersion() >= this.minVersion) break;
            }} catch (final kelondroException e) {
            	e.printStackTrace();
            	// eergency reset
            	yacyCore.log.logSevere("seed-db emergency reset", e);
            	try {
					database.clear();
					nextSeed = null;
					return null;
				} catch (final IOException e1) {
					// no recovery possible
					e1.printStackTrace();
					System.exit(-1);
				}
            }
            return seed;
        }

        public void remove() {
            throw new UnsupportedOperationException();
        }
        
    }

}
