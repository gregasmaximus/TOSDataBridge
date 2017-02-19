/*
Copyright (C) 2017 Jonathon Ogden   <jeog.dev@gmail.com>

This program is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with this program.  If not, see http://www.gnu.org/licenses.
*/

package io.github.jeog.tosdatabridge;

import io.github.jeog.tosdatabridge.TOSDataBridge.*;

import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.NativeLong;
import com.sun.jna.Pointer;

import java.lang.reflect.Array;
import java.util.*;

/**
 * DataBlocks are used to get real-time financial data from the TOS platform.
 * They use the JNA interface(CLib.java) to access the C API, wrapping the
 * underlying (conceptual) block of the C lib with an object-oriented java interface.
 * <p>
 * <strong>IMPORTANT:</strong> Before creating/using a DataBlock the underlying library
 * must be loaded AND connected to the backend Service/Engine and the TOS platform.
 * <ol>
 * <li> be sure you've installed the C mods (tosdb-setup.bat)
 * <li> start the Service/Engine (SC Start TOSDataBridge)
 * <li> start the TOS Platform
 * <li> call TOSDataBridge.init(...)
 * <li> create DataBlock(s)
 * </ol>
 * <p>
 * DataBlock works much the same as its python cousin (tosdb._win.TOSDB_DataBlock).
 * It's instantiated with a size parameter to indicate how much historical data is saved,
 * and a boolean to indicate whether it should record DateTime objects alongside its
 * primary data.
 * <p>
 * Once created 'topics' (data fields found in the Topic enum) and 'items' (symbols)
 * are added to create a matrix of 'streams', each the size of the block. The block can
 * be though of as a 3D object with the following dimensions:
 * <p>
 * &nbsp&nbsp&nbsp&nbsp (# of topics) &nbsp x &nbsp (# of items) &nbsp x &nbsp (block size)
 * <p>
 * As data flows into these streams the various 'get' methods are then used to
 * 1) examine the underlying state of the block/streams, and 2) pull data from
 * the block/streams in myriad ways.
 *
 * @author Jonathon Ogden
 * @version 0.7
 * @throws CLibException   If C Lib returned a 'native' error (CError.java)
 * @throws LibraryNotLoaded  If native C Lib has not been loaded via init(...)
 * @see TOSDataBridge
 * @see Topic
 * @see DateTime
 * @see <a href="https://github.com/jeog/TOSDataBridge/blob/master/README.md"> README </a>
 * @see <a href="https://github.com/jeog/TOSDataBridge/blob/master/README_JAVA.md"> README - JAVA API </a>
 * @see <a href="https://github.com/jeog/TOSDataBridge/blob/master/README_API.md"> README - C API </a>
 * @see <a href="https://github.com/jeog/TOSDataBridge/blob/master/README_SERVICE.md"> README - SERVICE </a>
 */
public class DataBlock {
    /* max size of non-data (e.g topics, labels) strings (excluding \0)*/
    public static final int MAX_STR_SZ = TOSDataBridge.MAX_STR_SZ;

    /* max size of data strings (getString, getStreamSnapshotStrings etc.) */
    public static final int STR_DATA_SZ = TOSDataBridge.STR_DATA_SZ;

    /* how much buffer 'padding' for the marker calls (careful changing default) */
    public static int MARKER_MARGIN_OF_SAFETY = TOSDataBridge.MARKER_MARGIN_OF_SAFETY;

    /* default timeout for communicating with the engine/platform */
    public static int DEF_TIMEOUT = TOSDataBridge.DEF_TIMEOUT;

    /**
     * Pair of primary data and DateTime object.
     * @param <T> type of primary data
     */
    public static class DateTimePair<T> extends Pair<T,DateTime> {
        public
        DateTimePair(T first, DateTime second){
            super(first, second);
        }
    }

    private String _name;
    private int _size;
    private boolean _dateTime;
    private int _timeout;
    private Set<String> _items;
    private Set<Topic> _topics;

    /**
     * DataBlock Constructor.
     *
     * @param size maximum amount of historical data block can hold
     * @throws CLibException
     * @throws LibraryNotLoaded
     */
    public
    DataBlock(int size) throws CLibException, LibraryNotLoaded {
        this(size, DEF_TIMEOUT);
    }

    /**
     * DataBlock Constructor.
     *
     * @param size maximum amount of historical data block can hold
     * @param timeout timeout of internal IPC and DDE mechanisms (milliseconds)
     * @throws LibraryNotLoaded
     * @throws CLibException
     */
    public
    DataBlock(int size, int timeout) throws LibraryNotLoaded, CLibException {
        this(size, false, timeout);
    }

    /* for internal use, depending on the type of class constructed */
    protected
    DataBlock(int size, boolean dateTime, int timeout) throws LibraryNotLoaded, CLibException {
        _size = size;
        _dateTime = dateTime;
        _timeout = timeout;
        _name = UUID.randomUUID().toString();
        _items = new HashSet<>();
        _topics = new HashSet<>();

        int err = TOSDataBridge.getCLibrary().TOSDB_CreateBlock(_name, size, dateTime, timeout);
        if (err != 0) {
            throw new CLibException("TOSDB_CreateBlock", err);
        }
    }

    public void
    close() throws CLibException, LibraryNotLoaded {
        int err = TOSDataBridge.getCLibrary().TOSDB_CloseBlock(_name);
        if (err != 0) {
            throw new CLibException("TOSDB_CloseBlock", err);
        }
    }

    protected void
    finalize() throws Throwable {
        try {
            close();
        } finally {
            super.finalize();
        }
    }

    public String
    getName() {
        return _name;
    }

    public boolean
    isUsingDateTime() {
        return _dateTime;
    }

    public int
    getTimeout() {
        return _timeout;
    }

    public int
    getBlockSize() throws LibraryNotLoaded, CLibException {
        int[] size = {0};
        int err = TOSDataBridge.getCLibrary().TOSDB_GetBlockSize(_name, size);
        if(err != 0) {
            throw new CLibException("TOSDB_GetBlockSize", err);
        }
        if(size[0] != _size) {
            throw new IllegalStateException("size != _size");
        }
        return size[0];
    }

    public void
    setBlockSize(int size) throws LibraryNotLoaded, CLibException {
        int err = TOSDataBridge.getCLibrary().TOSDB_SetBlockSize(_name, size);
        if(err != 0) {
            throw new CLibException("TOSDB_SetBlockSize", err);
        }
        _size = size;
    }

    public int
    getStreamOccupancy(String item, Topic topic) throws CLibException, LibraryNotLoaded {
        item = item.toUpperCase();
        _isValidItem(item);
        _isValidTopic(topic);
        int occ[] = {0};
        int err = TOSDataBridge.getCLibrary().TOSDB_GetStreamOccupancy(_name,item,topic.val,occ);
        if(err != 0) {
            throw new CLibException("TOSDB_GetStreamOccupancy", err);
        }
        return occ[0];
    }

    public Set<String>
    getItems() throws LibraryNotLoaded, CLibException {
        return _getItemTopicNames(true,false);
    }

    public Set<Topic>
    getTopics() throws LibraryNotLoaded, CLibException {
        return _getItemTopicNames(false,false);
    }

    public Set<String>
    getItemsPreCached() throws LibraryNotLoaded, CLibException {
        return _getItemTopicNames(true,true);
    }

    public Set<Topic>
    getTopicsPreCached() throws LibraryNotLoaded, CLibException {
        return _getItemTopicNames(false,true);
    }

    public void
    addItem(String item) throws LibraryNotLoaded, CLibException {
        /* check we are consistent with C lib */
        if(!_items.equals(getItems())) {
            throw new IllegalStateException("_items != getItems()");
        }
        int err = TOSDataBridge.getCLibrary().TOSDB_AddItem(_name, item);
        if(err != 0) {
            throw new CLibException("TOSDB_AddItem", err);
        }
        /* in case topics came out of pre-cache */
        if(_items.isEmpty() && _topics.isEmpty()) {
            _topics = getTopics();
        }
        _items = getItems();
    }

    public void
    addTopic(Topic topic) throws LibraryNotLoaded, CLibException {
        /* check we are consistent with C lib */
        if(!_topics.equals(getTopics())) {
            throw new IllegalStateException("_topics != getTopics()");
        }
        int err = TOSDataBridge.getCLibrary().TOSDB_AddTopic(_name, topic.val);
        if(err != 0) {
            throw new CLibException("TOSDB_AddTopic", err);
        }
        /* in case items came out of pre-cache */
        if(_topics.isEmpty() && _items.isEmpty()) {
            _items = getItems();
        }
        _topics = getTopics();
    }

    public void
    removeItem(String item) throws LibraryNotLoaded, CLibException {
        /* check we are consistent with C lib */
        if(!_items.equals(getItems())) {
            throw new IllegalStateException("_items != getItems()");
        }
        int err = TOSDataBridge.getCLibrary().TOSDB_RemoveItem(_name, item);
        if(err != 0) {
            throw new CLibException("TOSDB_RemoveItem", err);
        }
        _items = getItems();
        /* in case topics get sent to pre-cache */
        if(_items.isEmpty()) {
            _topics = getTopics();
        }
    }

    public void
    removeTopic(Topic topic) throws LibraryNotLoaded, CLibException {
        /* check we are consistent with C lib */
        if(!_topics.equals(getTopics())) {
            throw new IllegalStateException("_topics != getTopics()");
        }
        int err = TOSDataBridge.getCLibrary().TOSDB_RemoveTopic(_name, topic.val);
        if(err != 0) {
            throw new CLibException("TOSDB_RemoveTopic", err);
        }
        _topics = getTopics();
        /* in case items get sent to pre-cache */
        if(_topics.isEmpty()) {
            _items = getItems();
        }
    }

    public Long
    getLong(String item, Topic topic, boolean checkIndx)
            throws CLibException, LibraryNotLoaded, DataIndexException {
        return get(item,topic,0, checkIndx, false, Long.class);
    }

    public Long
    getLong(String item, Topic topic,int indx, boolean checkIndx)
            throws CLibException, LibraryNotLoaded, DataIndexException {
        return get(item,topic, indx, checkIndx, false, Long.class);
    }

    public Double
    getDouble(String item, Topic topic, boolean checkIndx)
            throws CLibException, LibraryNotLoaded, DataIndexException {
        return get(item,topic,0, checkIndx, false, Double.class);
    }

    public Double
    getDouble(String item, Topic topic,int indx, boolean checkIndx)
            throws CLibException, LibraryNotLoaded, DataIndexException {
        return get(item,topic, indx, checkIndx, false, Double.class);
    }

    public String
    getString(String item, Topic topic, boolean checkIndx)
            throws CLibException, LibraryNotLoaded, DataIndexException {
        return get(item,topic,0, checkIndx, false, String.class);
    }

    public String
    getString(String item, Topic topic,int indx, boolean checkIndx)
            throws CLibException, LibraryNotLoaded, DataIndexException {
        return get(item,topic, indx, checkIndx, false, String.class);
    }

    public Long[]
    getStreamSnapshotLongs(String item, Topic topic)
            throws LibraryNotLoaded, CLibException, DataIndexException {
        return getStreamSnapshot(item,topic,-1,0,true, false,Long.class);
    }

    public Long[]
    getStreamSnapshotLongs(String item, Topic topic, int end)
            throws LibraryNotLoaded, CLibException, DataIndexException {
        return getStreamSnapshot(item,topic,end,0,true, false,Long.class);
    }

    public Long[]
    getStreamSnapshotLongs(String item, Topic topic, int end, int beg)
            throws LibraryNotLoaded, CLibException, DataIndexException {
        return getStreamSnapshot(item,topic,end,beg,true,false,Long.class);
    }

    public Double[]
    getStreamSnapshotDoubles(String item, Topic topic)
            throws LibraryNotLoaded, CLibException, DataIndexException {
        return getStreamSnapshot(item,topic,-1,0,true, false,Double.class);
    }

    public Double[]
    getStreamSnapshotDoubles(String item, Topic topic, int end)
            throws LibraryNotLoaded, CLibException, DataIndexException {
        return getStreamSnapshot(item,topic,end,0,true, false,Double.class);
    }

    public Double[]
    getStreamSnapshotDoubles(String item, Topic topic, int end, int beg)
            throws LibraryNotLoaded, CLibException, DataIndexException {
        return getStreamSnapshot(item,topic,end,beg,true,false,Double.class);
    }

    public String[]
    getStreamSnapshotStrings(String item, Topic topic)
            throws LibraryNotLoaded, CLibException, DataIndexException {
        return getStreamSnapshot(item,topic,-1,0,true,false,String.class);
    }

    public String[]
    getStreamSnapshotStrings(String item, Topic topic, int end)
            throws LibraryNotLoaded, CLibException, DataIndexException {
        return getStreamSnapshot(item,topic,end,0,true,false,String.class);
    }

    public String[]
    getStreamSnapshotStrings(String item, Topic topic, int end, int beg)
            throws LibraryNotLoaded, CLibException, DataIndexException {
        return getStreamSnapshot(item,topic,end,beg,true,false,String.class);
    }

    public Long[]
    getStreamSnapshotLongsFromMarker(String item, Topic topic)
            throws LibraryNotLoaded, CLibException, DataIndexException, DirtyMarkerException {
        return getStreamSnapshotFromMarker(item,topic,0,true,false,Long.class);
    }

    public Long[]
    getStreamSnapshotLongsFromMarker(String item, Topic topic, int beg)
            throws LibraryNotLoaded, CLibException, DataIndexException, DirtyMarkerException {
        return getStreamSnapshotFromMarker(item,topic,beg,true,false,Long.class);
    }

    public Double[]
    getStreamSnapshotDoublesFromMarker(String item, Topic topic)
            throws LibraryNotLoaded, CLibException, DataIndexException, DirtyMarkerException {
        return getStreamSnapshotFromMarker(item,topic,0,true,false,Double.class);
    }

    public Double[]
    getStreamSnapshotDoublesFromMarker(String item, Topic topic, int beg)
            throws LibraryNotLoaded, CLibException, DataIndexException, DirtyMarkerException {
        return getStreamSnapshotFromMarker(item,topic,beg,true,false,Double.class);
    }

    public String[]
    getStreamSnapshotStringsFromMarker(String item, Topic topic)
            throws LibraryNotLoaded, CLibException, DataIndexException, DirtyMarkerException {
        return getStreamSnapshotFromMarker(item,topic,0,true,false,String.class);
    }

    public String[]
    getStreamSnapshotStringsFromMarker(String item, Topic topic, int beg)
            throws LibraryNotLoaded, CLibException, DataIndexException, DirtyMarkerException {
        return getStreamSnapshotFromMarker(item,topic,beg,true,false,String.class);
    }

    public Long[]
    getStreamSnapshotLongsFromMarkerIgnoreDirty(String item, Topic topic)
            throws LibraryNotLoaded, CLibException, DataIndexException {
        return getStreamSnapshotFromMarkerIgnoreDirty(item,topic,0,false,Long.class);
    }

    public Long[]
    getStreamSnapshotLongsFromMarkerIgnoreDirty(String item, Topic topic, int beg)
            throws LibraryNotLoaded, CLibException, DataIndexException {
        return getStreamSnapshotFromMarkerIgnoreDirty(item,topic,beg,false,Long.class);
    }

    public Double[]
    getStreamSnapshotDoublesFromMarkerIgnoreDirty(String item, Topic topic)
            throws LibraryNotLoaded, CLibException, DataIndexException {
        return getStreamSnapshotFromMarkerIgnoreDirty(item,topic,0,false,Double.class);
    }

    public Double[]
    getStreamSnapshotDoublesFromMarkerIgnoreDirty(String item, Topic topic, int beg)
            throws LibraryNotLoaded, CLibException, DataIndexException {
        return getStreamSnapshotFromMarkerIgnoreDirty(item, topic, beg, false, Double.class);
    }

    public String[]
    getStreamSnapshotStringsFromMarkerIgnoreDirty(String item, Topic topic)
            throws LibraryNotLoaded, CLibException, DataIndexException {
        return getStreamSnapshotFromMarkerIgnoreDirty(item,topic,0,false,String.class);
    }

    public String[]
    getStreamSnapshotStringsFromMarkerIgnoreDirty(String item, Topic topic, int beg)
            throws LibraryNotLoaded, CLibException, DataIndexException {
        return getStreamSnapshotFromMarkerIgnoreDirty(item,topic,beg,false,String.class);
    }

    public Map<String,String>
    getItemFrame(Topic topic) throws CLibException, LibraryNotLoaded {
        return _getFrame(topic, true, false);
    }

    public Map<Topic,String>
    getTopicFrame(String item) throws CLibException, LibraryNotLoaded {
        return _getFrame(item, false, false);
    }

    public Map<String, Map<Topic,String>>
    getTotalFrame() throws LibraryNotLoaded, CLibException {
        Map<String, Map<Topic, String>> frame = new HashMap<>();
        for (String item : getItems()) {
            Map<Topic, String> tf = getTopicFrame(item);
            frame.put(item, tf);
        }
        return frame;
    }

    @SuppressWarnings("unchecked")
    protected final <T> T
    get(String item, Topic topic,int indx, boolean checkIndx, boolean withDateTime,
            Class<?> valType) throws LibraryNotLoaded, CLibException, DataIndexException
    {
        final CLib lib = TOSDataBridge.getCLibrary();
        item = item.toUpperCase();
        _isValidItem(item);
        _isValidTopic(topic);

        if(indx < 0) {
            indx += _size;
        }
        if(indx >= _size) {
            throw new DataIndexException("indx >= _size in DataBlock");
        }

        int occ = getStreamOccupancy(item, topic);
        if(checkIndx && indx >= occ) {
            throw new DataIndexException("Can not get data at indx: " + String.valueOf(indx)
                    + ", occupancy only: " + String.valueOf(occ));
        }

        if( valType.equals(Long.class) ) {
            return _getLong(item,topic,indx,withDateTime,lib);
        }else if( valType.equals(Double.class) ) {
            return _getDouble(item,topic,indx,withDateTime,lib);
        }else {
            return _getString(item,topic,indx,withDateTime,lib);
        }
    }

    @SuppressWarnings("unchecked")
    private <T> T
    _getLong(String item, Topic topic,int indx, boolean withDateTime, final CLib lib)
            throws CLibException
    {
        DateTime ptrDTS =  withDateTime ? new DateTime() : null;
        long[] ptrVal = {0};
        int err = lib.TOSDB_GetLongLong(_name, item, topic.val, indx, ptrVal, ptrDTS);
        if(err != 0) {
            throw new CLibException("TOSDB_GetLongLongs", err);
        }
        return (T)(withDateTime ? new DateTimePair<>(ptrVal[0],ptrDTS) : ptrVal[0]);
    }

    @SuppressWarnings("unchecked")
    private <T> T
    _getDouble(String item, Topic topic,int indx, boolean withDateTime, final CLib lib)
            throws CLibException
    {
        DateTime ptrDTS =  withDateTime ? new DateTime() : null;
        double[] ptrVal = {0};
        int err = lib.TOSDB_GetDouble(_name, item, topic.val, indx, ptrVal, ptrDTS);
        if(err != 0) {
            throw new CLibException("TOSDB_GetDoubles", err);
        }
        return (T)(withDateTime ? new DateTimePair<>(ptrVal[0],ptrDTS) : ptrVal[0]);
    }

    @SuppressWarnings("unchecked")
    private <T> T
    _getString(String item, Topic topic,int indx, boolean withDateTime, final CLib lib)
            throws CLibException
    {
        DateTime ptrDTS =  withDateTime ? new DateTime() : null;
        byte[] ptrVal = new byte[STR_DATA_SZ + 1];
        int err = lib.TOSDB_GetString(_name, item, topic.val, indx, ptrVal, STR_DATA_SZ + 1, ptrDTS);
        if(err != 0) {
            throw new CLibException("TOSDB_GetStrings", err);
        }
        return (T)(withDateTime ? new DateTimePair<>(Native.toString(ptrVal),ptrDTS)
                : Native.toString(ptrVal));
    }

    @SuppressWarnings("unchecked")
    protected final <T> T[]
    getStreamSnapshot(String item, Topic topic, int end, int beg, boolean smartSize,
                      boolean withDateTime, Class<?> valType)
            throws LibraryNotLoaded, CLibException, DataIndexException
    {
        final CLib lib = TOSDataBridge.getCLibrary();
        item = item.toUpperCase();
        _isValidItem(item);
        _isValidTopic(topic);

        if(end < 0) {
            end += _size;
        }
        if(beg < 0) {
            beg += _size;
        }
        int size = end - beg + 1;
        if(beg < 0 || end < 0
                || beg >= _size || end >= _size
                || size <= 0){
            throw new DataIndexException("invalid 'beg' and/or 'end' index");
        }

        if(smartSize){
            int occ = getStreamOccupancy(item, topic);
            if(occ == 0 || occ <= beg) {
                return (T[]) (withDateTime ? new DateTimePair[]{} : Array.newInstance(valType, 0));
            }
            end = Math.min(end, occ-1);
            beg = Math.min(beg, occ-1);
            size = end - beg + 1;
        }

        if( valType.equals(Long.class) ){
            return _getStreamSnapshotLongs(item, topic, end, beg, withDateTime, size, lib);
        }else if( valType.equals(Double.class) ){
            return _getStreamSnapshotDoubles(item, topic, end, beg, withDateTime, size, lib);
        }else{
            return _getStreamSnapshotStrings(item, topic, end, beg, withDateTime, size, lib);
        }
    }

    @SuppressWarnings("unchecked")
    protected  <T> T[]
    _getStreamSnapshotLongs(String item, Topic topic, int end, int beg, boolean withDateTime,
                            int size, final CLib lib)
            throws LibraryNotLoaded, CLibException, DataIndexException
    {
        DateTime[] arrayDTS = (withDateTime ? new DateTime[size] : null);
        long[] arrayVals = new long[size];
        int err = lib.TOSDB_GetStreamSnapshotLongLongs(_name, item, topic.val, arrayVals, size,
                arrayDTS, end, beg);
        if(err != 0) {
            throw new CLibException("TOSDB_GetStreamSnapshotLongs", err);
        }
        T[] data = (T[])(withDateTime ? new DateTimePair[size] : new Long[size]);
        for(int i = 0; i < size; ++i) {
            data[i] = (T)(withDateTime ? new DateTimePair<>(arrayVals[i], arrayDTS[i]) : arrayVals[i]);
        }
        return data;
    }

    @SuppressWarnings("unchecked")
    protected  <T> T[]
    _getStreamSnapshotDoubles(String item, Topic topic, int end, int beg, boolean withDateTime,
                              int size, final CLib lib)
            throws LibraryNotLoaded, CLibException, DataIndexException
    {
        DateTime[] arrayDTS = (withDateTime ? new DateTime[size] : null);
        double[] arrayVals = new double[size];
        int err = lib.TOSDB_GetStreamSnapshotDoubles(_name, item, topic.val, arrayVals, size,
                arrayDTS, end, beg);
        if(err != 0) {
            throw new CLibException("TOSDB_GetStreamSnapshotDoubles", err);
        }
        T[] data = (T[])(withDateTime ? new DateTimePair[size] : new Double[size]);
        for(int i = 0; i < size; ++i) {
            data[i] = (T)(withDateTime ? new DateTimePair<>(arrayVals[i], arrayDTS[i]) : arrayVals[i]);
        }
        return data;
    }

    @SuppressWarnings("unchecked")
    protected  <T> T[]
    _getStreamSnapshotStrings(String item, Topic topic, int end, int beg, boolean withDateTime,
                              int size, final CLib lib)
            throws LibraryNotLoaded, CLibException, DataIndexException
    {
        DateTime[] arrayDTS = (withDateTime ? new DateTime[size] : null);
        Pointer[] arrayVals = new Pointer[size];
        for(int i = 0; i < size; ++i){
            arrayVals[i] = new Memory(STR_DATA_SZ+1);
        }
        int err = lib.TOSDB_GetStreamSnapshotStrings(_name, item, topic.val, arrayVals,
                size, STR_DATA_SZ + 1, arrayDTS, end, beg);
        if(err != 0) {
            throw new CLibException("TOSDB_GetStreamSnapshotStrings", err);
        }
        T[] data = (T[])(withDateTime ? new DateTimePair[size] : new String[size]);
        for(int i = 0; i < size; ++i) {
            data[i] = (T)(withDateTime ? new DateTimePair<>(arrayVals[i].getString(0), arrayDTS[i])
                    : arrayVals[i].getString(0));
        }
        return data;
    }

    protected final <T> T[]
    getStreamSnapshotFromMarkerIgnoreDirty(String item, Topic topic, int beg, boolean withDateTime,
                                           Class<?> valType)
            throws LibraryNotLoaded, CLibException, DataIndexException 
    {
        try {
            return getStreamSnapshotFromMarker(item,topic,beg,false,withDateTime,valType);
        } catch (DirtyMarkerException e) {
            // this call can't throw DirtyMarkerException
            // allows us to remove from message signature
        }
        /* SHOULD NEVER GET HERE */
        throw new RuntimeException("...IgnoreDirty failed to ignore dirty marker");
    }

    @SuppressWarnings("unchecked")
    protected final <T> T[]
    getStreamSnapshotFromMarker(String item, Topic topic, int beg, boolean throwIfDataLost,
                                 boolean withDateTime, Class<?> valType)
            throws LibraryNotLoaded, CLibException, DataIndexException, DirtyMarkerException
    {
        final CLib lib = TOSDataBridge.getCLibrary();
        int err;

        item = item.toUpperCase();
        _isValidItem(item);
        _isValidTopic(topic);

        if(beg < 0) {
            beg += _size;
        }
        if(beg < 0 || beg >= _size) {
            throw new DataIndexException("invalid 'beg' index argument");
        }

        int[] ptrIsDirty = {0};
        err = lib.TOSDB_IsMarkerDirty(_name, item, topic.val, ptrIsDirty);
        if(err != 0) {
            throw new CLibException("TOSDB_IsMarkerDirty", err);
        }
        if(ptrIsDirty[0] != 0 && throwIfDataLost) {
            throw new DirtyMarkerException();
        }

        long[] ptrMarkerPos = {0};
        err = lib.TOSDB_GetMarkerPosition(_name, item, topic.val, ptrMarkerPos);
        if(err != 0) {
            throw new CLibException("TOSDB_GetMarkerPosition", err);
        }
        long curSz = ptrMarkerPos[0] - beg + 1;
        if(curSz < 0) {
            return (T[]) (withDateTime ? new DateTimePair[]{} : Array.newInstance(valType, 0));
        }
        int safeSz = (int)curSz + MARKER_MARGIN_OF_SAFETY;

        if( valType.equals(Long.class) ){
            return _getStreamSnapshotLongsFromMarker(item,topic,beg,withDateTime,
                    throwIfDataLost,safeSz, lib);
        }else if( valType.equals(Double.class) ){
            return _getStreamSnapshotDoublesFromMarker(item,topic,beg,withDateTime,
                    throwIfDataLost,safeSz, lib);
        }else {
            return _getStreamSnapshotStringsFromMarker(item, topic, beg, withDateTime,
                    throwIfDataLost, safeSz, lib);
        }
    }

    @SuppressWarnings("unchecked")
    private <T> T[]
    _getStreamSnapshotLongsFromMarker(String item, Topic topic, int beg, boolean withDateTime,
                                      boolean throwIfDataLost, int safeSz, final CLib lib)
            throws CLibException, LibraryNotLoaded, DirtyMarkerException
    {
        DateTime[] arrayDTS = (withDateTime ? new DateTime[safeSz] : null);
        NativeLong[] ptrGetSz = {new NativeLong(0)};
        long[] arrayVals = new long[safeSz];
        int err = lib.TOSDB_GetStreamSnapshotLongLongsFromMarker(
                _name, item, topic.val, arrayVals, safeSz, arrayDTS, beg, ptrGetSz);
        if(err != 0) {
            throw new CLibException("TOSDB_GetStreamSnapshotLongsFromMarker", err);
        }
        int szGot = (int)_handleSzGotFromMarker(ptrGetSz[0],throwIfDataLost);
        T[] data = (T[])(withDateTime ? new DateTimePair[szGot] : new Long[szGot]);
        for(int i = 0; i < szGot; ++i) {
            data[i] = (T)(withDateTime ? new DateTimePair<>(arrayVals[i], arrayDTS[i]) : arrayVals[i]);
        }
        return data;
    }

    @SuppressWarnings("unchecked")
    private <T> T[]
    _getStreamSnapshotDoublesFromMarker(String item, Topic topic, int beg, boolean withDateTime,
                                         boolean throwIfDataLost, int safeSz, final CLib lib)
            throws CLibException, LibraryNotLoaded, DirtyMarkerException
    {
        DateTime[] arrayDTS = (withDateTime ? new DateTime[safeSz] : null);
        NativeLong[] ptrGetSz = {new NativeLong(0)};
        double[] arrayVals = new double[safeSz];
        int err = lib.TOSDB_GetStreamSnapshotDoublesFromMarker(
                _name, item, topic.val, arrayVals, safeSz, arrayDTS, beg, ptrGetSz);
        if(err != 0) {
            throw new CLibException("TOSDB_GetStreamSnapshotDoublesFromMarker", err);
        }
        int szGot = (int)_handleSzGotFromMarker(ptrGetSz[0],throwIfDataLost);
        T[] data = (T[])(withDateTime ? new DateTimePair[szGot] : new Double[szGot]);
        for(int i = 0; i < szGot; ++i) {
            data[i] = (T)(withDateTime ? new DateTimePair<>(arrayVals[i], arrayDTS[i]) : arrayVals[i]);
        }
        return data;
    }

    @SuppressWarnings("unchecked")
    private <T> T[]
    _getStreamSnapshotStringsFromMarker(String item, Topic topic, int beg, boolean withDateTime,
                                         boolean throwIfDataLost, int safeSz, final CLib lib)
            throws CLibException, LibraryNotLoaded, DirtyMarkerException
    {
        Pointer[] arrayVals = new Pointer[safeSz];
        DateTime[] arrayDTS = (withDateTime ? new DateTime[safeSz] : null);
        NativeLong[] ptrGetSz = {new NativeLong(0)};
        for(int i = 0; i < safeSz; ++i){
            arrayVals[i] = new Memory(STR_DATA_SZ+1);
        }
        int err = lib.TOSDB_GetStreamSnapshotStringsFromMarker(
                _name, item, topic.val, arrayVals, safeSz, STR_DATA_SZ + 1, arrayDTS, beg, ptrGetSz);
        if(err != 0) {
            throw new CLibException("TOSDB_GetStreamSnapshotStringsFromMarker", err);
        }
        int szGot = (int)_handleSzGotFromMarker(ptrGetSz[0],throwIfDataLost);
        T[] data = (T[])(withDateTime ? new DateTimePair[szGot] : new String[szGot]);
        for(int i = 0; i < szGot; ++i) {
            data[i] = (T)(withDateTime ? new DateTimePair<>(arrayVals[i].getString(0), arrayDTS[i])
                    : arrayVals[i].getString(0));
        }
        return data;
    }


    protected final <T,E,X> Map<X,T>
    _getFrame(E e, boolean itemFrame, boolean withDateTime)
            throws CLibException, LibraryNotLoaded
    {
        final CLib lib = TOSDataBridge.getCLibrary();

        if(itemFrame) {
            _isValidTopic((Topic) e);
        }else {
            _isValidItem(((String) e).toUpperCase());
        }

        int n = itemFrame ? _getItemCount() : _getTopicCount();

        Pointer[] arrayVals = new Pointer[n];
        Pointer[] arrayLabels = new Pointer[n];
        for(int i = 0; i < n; ++i){
            arrayVals[i] = new Memory(STR_DATA_SZ+1);
            arrayLabels[i] = new Memory(MAX_STR_SZ+1);
        }
        DateTime[] arrayDTS = (withDateTime ? new DateTime[n] : null);

        int err = itemFrame
                ? lib.TOSDB_GetItemFrameStrings(_name, ((Topic)e).toString(), arrayVals, n,
                        STR_DATA_SZ + 1, arrayLabels, MAX_STR_SZ + 1, arrayDTS)
                : lib.TOSDB_GetTopicFrameStrings(_name, ((String)e).toUpperCase(), arrayVals, n,
                        STR_DATA_SZ + 1, arrayLabels, MAX_STR_SZ + 1, arrayDTS);
        if(err != 0) {
            throw new CLibException("TOSDB_Get" + (itemFrame ? "Item" : "Topic") + "FrameStrings",
                    err);
        }

        Map<X,T> frame = new HashMap<>();
        for(int i = 0; i < n; ++i){
            @SuppressWarnings("unchecked")
            T val = (T)(withDateTime ? new DateTimePair(arrayVals[i].getString(0), arrayDTS[i])
                                     : arrayVals[i].getString(0));
            String label = arrayLabels[i].getString(0);
            @SuppressWarnings("unchecked")
            X lab = (X)(itemFrame ? label : Topic.toEnum(label));
            frame.put(lab, val);
        }
        return frame;
    }

    private long
    _handleSzGotFromMarker(NativeLong l, boolean throwIfDataLost) throws DirtyMarkerException {
        long szGot = l.longValue();
        if(szGot < 0){
            if(throwIfDataLost) {
                throw new DirtyMarkerException();
            }else {
                szGot *= -1;
            }
        }
        return szGot;
    }
    
    private void
    _isValidItem(String item) throws IllegalArgumentException, CLibException, LibraryNotLoaded {
        if(_items.isEmpty()) {
            _items = getItems();
        }
        if(!_items.contains(item)) {
            throw new IllegalArgumentException("item " + item + " not found");
        }
    }

    private void
    _isValidTopic(Topic topic) throws IllegalArgumentException, CLibException, LibraryNotLoaded {
        if(_topics.isEmpty()) {
            _topics = getTopics();
        }
        if(!_topics.contains(topic)) {
            throw new IllegalArgumentException("topic " + topic + " not found");
        }
    }

    private int
    _getItemCount() throws CLibException, LibraryNotLoaded {
        int[] count = {0};
        int err = TOSDataBridge.getCLibrary().TOSDB_GetItemCount(_name, count);
        if(err != 0) {
            throw new CLibException("TOSDB_GetItemCount", err);
        }
        return count[0];
    }

    private int
    _getTopicCount() throws CLibException, LibraryNotLoaded {
        int[] count = {0};
        int err = TOSDataBridge.getCLibrary().TOSDB_GetTopicCount(_name, count);
        if(err != 0) {
            throw new CLibException("TOSDB_GetTopicCount", err);
        }
        return count[0];
    }

    private int
    _getItemPreCachedCount() throws CLibException, LibraryNotLoaded {
        int[] count = {0};
        int err = TOSDataBridge.getCLibrary().TOSDB_GetPreCachedItemCount(_name, count);
        if(err != 0) {
            throw new CLibException("TOSDB_GetPreCachedItemCount", err);
        }
        return count[0];
    }

    private int
    _getTopicPreCachedCount() throws CLibException, LibraryNotLoaded {
        int[] count = {0};
        int err = TOSDataBridge.getCLibrary().TOSDB_GetPreCachedTopicCount(_name, count);
        if(err != 0) {
            throw new CLibException("TOSDB_GetPreCachedTopicCount", err);
        }
        return count[0];
    }

    private <T> Set<T>
    _getItemTopicNames(boolean useItemVersion, boolean usePreCachedVersion)
            throws LibraryNotLoaded, CLibException
    {
        final CLib lib = TOSDataBridge.getCLibrary();
        int err;
        int n;

        if (useItemVersion) {
            n = usePreCachedVersion ? _getItemPreCachedCount() : _getItemCount();
        }else{
            n = usePreCachedVersion ? _getTopicPreCachedCount() : _getTopicCount();
        }
        if(n < 1) {
            return new HashSet<T>();
        }

        Pointer[] arrayVals = new Pointer[n];
        for(int i = 0; i < n; ++i){
            arrayVals[i] = new Memory(MAX_STR_SZ+1);
        }

        if (useItemVersion) {
            err = usePreCachedVersion
                    ? lib.TOSDB_GetPreCachedItemNames(_name, arrayVals, n, MAX_STR_SZ)
                    : lib.TOSDB_GetItemNames(_name, arrayVals, n, MAX_STR_SZ);

        }else{
            err = usePreCachedVersion
                    ? lib.TOSDB_GetPreCachedTopicNames(_name, arrayVals, n, MAX_STR_SZ)
                    : lib.TOSDB_GetTopicNames(_name, arrayVals, n, MAX_STR_SZ);
        }
        if(err != 0) {
            throw new CLibException("TOSDB_Get" + (usePreCachedVersion ? "PreCached" : "")
                    + (useItemVersion ? "Item" : "Topic") + "Names", err);
        }

        Set<T> retVals = new HashSet<>();
        for(int i = 0; i < n; ++i){
            String s = arrayVals[i].getString(0);
            @SuppressWarnings("unchecked")
            T val = (T)(useItemVersion ? s : Topic.toEnum(s));
            retVals.add(val);
        }
        return retVals;
    }
}