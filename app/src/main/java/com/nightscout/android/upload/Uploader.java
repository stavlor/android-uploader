package com.nightscout.android.upload;

import android.content.Context;
import android.util.Log;

import com.mongodb.MongoClientURI;
import com.mongodb.MongoException;
import com.nightscout.android.R;
import com.nightscout.android.ToastReceiver;
import com.nightscout.android.drivers.AndroidUploaderDevice;
import com.nightscout.android.events.AndroidEventReporter;
import com.nightscout.core.dexcom.Utils;
import com.nightscout.core.dexcom.records.CalRecord;
import com.nightscout.core.dexcom.records.GlucoseDataSet;
import com.nightscout.core.dexcom.records.MeterRecord;
import com.nightscout.core.drivers.AbstractUploaderDevice;
import com.nightscout.core.events.EventReporter;
import com.nightscout.core.events.EventSeverity;
import com.nightscout.core.events.EventType;
import com.nightscout.core.model.CalibrationEntry;
import com.nightscout.core.model.G4Download;
import com.nightscout.core.model.MeterEntry;
import com.nightscout.core.preferences.NightscoutPreferences;
import com.nightscout.core.upload.BaseUploader;
import com.nightscout.core.upload.MongoUploader;
import com.nightscout.core.upload.RestLegacyUploader;
import com.nightscout.core.upload.RestV1Uploader;
import com.squareup.wire.Message;

import org.joda.time.DateTime;

import java.lang.reflect.InvocationTargetException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;

import static net.tribe7.common.base.Preconditions.checkNotNull;

public class Uploader {
    private static final String LOG_TAG = Uploader.class.getSimpleName();

    private final List<BaseUploader> uploaders;
    private boolean allUploadersInitalized = true;
    private EventReporter reporter;
    private Context context;
    protected NightscoutPreferences preferences;

    public Uploader(Context context, NightscoutPreferences preferences) {
        checkNotNull(context);
        this.preferences = preferences;
        this.context = context;
        reporter = AndroidEventReporter.getReporter(context);
        uploaders = new ArrayList<>();
        if (preferences.isMongoUploadEnabled()) {
            allUploadersInitalized &= initializeMongoUploader(context, preferences);
        }
        if (preferences.isRestApiEnabled()) {
            allUploadersInitalized &= initializeRestUploaders(context, preferences);
        }
        this.context = context;
    }

    public boolean areAllUploadersInitalized() {
        return allUploadersInitalized;
    }

    private boolean initializeMongoUploader(Context context, NightscoutPreferences preferences) {
        String dbURI = preferences.getMongoClientUri();
        String collectionName = preferences.getMongoCollection();
        String dsCollectionName = preferences.getMongoDeviceStatusCollection();
        checkNotNull(collectionName);
        checkNotNull(dsCollectionName);
        MongoClientURI uri;
        try {
            uri = new MongoClientURI(dbURI);
        } catch (IllegalArgumentException e) {
            reporter.report(EventType.UPLOADER, EventSeverity.ERROR,
                    context.getString(R.string.unknown_mongo_host));
            Log.e(LOG_TAG, "Error creating mongo client uri for " + dbURI + ".", e);
            context.sendBroadcast(ToastReceiver.createIntent(context, R.string.unknown_mongo_host));
            return false;
        } catch (NullPointerException e) {
            reporter.report(EventType.UPLOADER, EventSeverity.ERROR,
                    context.getString(R.string.unknown_mongo_host));
            Log.e(LOG_TAG, "Error creating mongo client uri for null value.", e);
            context.sendBroadcast(ToastReceiver.createIntent(context, R.string.unknown_mongo_host));
            return false;
        } catch (StringIndexOutOfBoundsException e) {
            reporter.report(EventType.UPLOADER, EventSeverity.ERROR,
                    context.getString(R.string.unknown_mongo_host));
            Log.e(LOG_TAG, "Error creating mongo client uri for null value.", e);
            context.sendBroadcast(ToastReceiver.createIntent(context, R.string.unknown_mongo_host));
            return false;
        }
        uploaders.add(new MongoUploader(preferences, uri, collectionName, dsCollectionName));
        return true;
    }

    private boolean initializeRestUploaders(Context context, NightscoutPreferences preferences) {
        List<String> baseUrisSetting = preferences.getRestApiBaseUris();
        List<URI> baseUris = new ArrayList<>();
        boolean allInitialized = true;
        for (String baseURLSetting : baseUrisSetting) {
            String baseUriString = baseURLSetting.trim();
            if (baseUriString.isEmpty()) continue;
            try {
                baseUris.add(URI.create(baseUriString));
            } catch (IllegalArgumentException e) {
                reporter.report(EventType.UPLOADER, EventSeverity.ERROR,
                        context.getString(R.string.illegal_rest_url));
                Log.e(LOG_TAG, "Error creating rest uri from preferences.", e);
                context.sendBroadcast(
                        ToastReceiver.createIntent(context, R.string.illegal_rest_url));
            }
        }

        for (URI baseUri : baseUris) {
            if (baseUri.getPath().contains("v1")) {
                try {
                    uploaders.add(new RestV1Uploader(preferences, baseUri));
                } catch (IllegalArgumentException e) {
                    Log.e(LOG_TAG, "Error initializing rest v1 uploader.", e);
                    allInitialized &= false;
                    context.sendBroadcast(
                            ToastReceiver.createIntent(context, R.string.illegal_rest_url));
                }
            } else {
                uploaders.add(new RestLegacyUploader(preferences, baseUri));
            }
        }
        return allInitialized;
    }

    public boolean upload(G4Download download, int numRecords) {
        long refTime = DateTime.parse(download.download_timestamp).getMillis();
        List<CalibrationEntry> calList = Utils.filterRecords(numRecords, download.cal);
        List<MeterEntry> meterList = Utils.filterRecords(numRecords, download.meter);
        List<CalRecord> calRecords = new ArrayList<>();
        List<MeterRecord> meterRecords = new ArrayList<>();
        if (download.receiver_system_time_sec != null) {
            calRecords = asRecordList(calList, CalRecord.class, download.receiver_system_time_sec, refTime);
            meterRecords = asRecordList(meterList, MeterRecord.class, download.receiver_system_time_sec, refTime);
        }


        List<GlucoseDataSet> glucoseDataSets = new ArrayList<>();
        if (download.sgv.size() > 0) {
            glucoseDataSets = Utils.mergeGlucoseDataRecords(download, numRecords);
        }

        int receiverBattery = (download.receiver_battery != null) ? download.receiver_battery : -1;

        return upload(glucoseDataSets, meterRecords, calRecords, receiverBattery);
    }

    public <T, U extends Message> List<T> asRecordList(List<U> entryList, Class<T> clazz, long rcvrTime, long refTime) {
        List<T> result = new ArrayList<>();
        for (U entry : entryList) {
            try {
                result.add(clazz.getConstructor(entry.getClass(), long.class, long.class).newInstance(entry, rcvrTime, refTime));
            } catch (InstantiationException | IllegalAccessException | InvocationTargetException | NoSuchMethodException e) {
                // nom
                Log.w(LOG_TAG, "Exception " + e);
            }
        }
        return result;
    }

    public boolean upload(G4Download download) {
        long refTime = DateTime.parse(download.download_timestamp).getMillis();
        List<GlucoseDataSet> glucoseDataSets = Utils.mergeGlucoseDataRecords(download.sgv, download.sensor, download.receiver_system_time_sec, refTime);
        List<MeterRecord> meterRecords = asRecordList(download.meter, MeterRecord.class, download.receiver_system_time_sec, refTime);
        List<CalRecord> calRecords = asRecordList(download.cal, CalRecord.class, download.receiver_system_time_sec, refTime);
        return upload(glucoseDataSets, meterRecords, calRecords, download.receiver_battery);
    }

    private boolean upload(List<GlucoseDataSet> glucoseDataSets,
                           List<MeterRecord> meterRecords,
                           List<CalRecord> calRecords, int rcvrBat) {

        AbstractUploaderDevice deviceStatus = AndroidUploaderDevice.getUploaderDevice(context);

        boolean allSuccessful = true;
        for (BaseUploader uploader : uploaders) {
            // TODO(klee): capture any exceptions here so that all configured uploaders will attempt
            // to upload
            try {
                allSuccessful &= uploader.uploadRecords(glucoseDataSets, meterRecords, calRecords, deviceStatus, rcvrBat);
                if (allSuccessful) {
                    reporter.report(EventType.UPLOADER, EventSeverity.INFO,
                            String.format(context.getString(R.string.event_success_upload),
                                    uploader.getIdentifier()));
                } else {
                    reporter.report(EventType.UPLOADER, EventSeverity.ERROR,
                            String.format(context.getString(R.string.event_fail_upload),
                                    uploader.getIdentifier()));
                }
                // TODO Why is this exception handler here?
            } catch (MongoException e) {
                // Credentials error - user name or password is incorrect.
                if (e.getCode() == 18) {
                    reporter.report(EventType.UPLOADER, EventSeverity.ERROR,
                            context.getString(R.string.event_mongo_invalid_credentials));
                } else {
                    reporter.report(EventType.UPLOADER, EventSeverity.ERROR,
                            String.format(context.getString(R.string.event_fail_upload),
                                    uploader.getIdentifier()));
                }
                allSuccessful &= false;
            }
        }

        return allSuccessful;
    }

    public List<BaseUploader> getUploaders() {
        return uploaders;
    }

    protected boolean areAllUploadersInitialized() {
        return allUploadersInitalized;
    }
}
