package org.workspace.casca;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import nodomain.freeyourgadget.gadgetbridge.GBApplication;
import nodomain.freeyourgadget.gadgetbridge.activities.HeartRateUtils;
import nodomain.freeyourgadget.gadgetbridge.database.DBHandler;
import nodomain.freeyourgadget.gadgetbridge.devices.DeviceCoordinator;
import nodomain.freeyourgadget.gadgetbridge.devices.SampleProvider;
import nodomain.freeyourgadget.gadgetbridge.devices.TimeSampleProvider;
import nodomain.freeyourgadget.gadgetbridge.entities.DaoSession;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.model.ActivityKind;
import nodomain.freeyourgadget.gadgetbridge.model.ActivitySample;
import nodomain.freeyourgadget.gadgetbridge.model.HeartRateSample;
import nodomain.freeyourgadget.gadgetbridge.model.SleepScoreSample;
import nodomain.freeyourgadget.gadgetbridge.model.Spo2Sample;
import nodomain.freeyourgadget.gadgetbridge.model.StressSample;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Summarises what Gadgetbridge already stored for one device over the last
 * hours: steps, heart rate, sleep, stress, SpO2. Reads the database only;
 * the band itself is not contacted (band_sync fetches new data). */
final class CascaHealth {
    private static final DateTimeFormatter ISO=DateTimeFormatter.ISO_OFFSET_DATE_TIME;
    private CascaHealth(){}

    static JSONObject read(GBDevice device,int hours)throws JSONException{
        ZoneId zone=ZoneId.systemDefault();
        long toMs=System.currentTimeMillis(),fromMs=toMs-hours*3_600_000L;
        JSONObject out=new JSONObject()
            .put("from",format(fromMs,zone)).put("to",format(toMs,zone)).put("hours",hours);
        DeviceCoordinator coordinator=device.getDeviceCoordinator();
        try(DBHandler db=GBApplication.acquireDB()){
            DaoSession session=db.getDaoSession();
            out.put("activity",activity(coordinator.getSampleProvider(device,session),fromMs,toMs,hours,zone));
            out.put("resting_heart_rate",heartRates(coordinator.getHeartRateRestingSampleProvider(device,session),fromMs,toMs,zone));
            out.put("stress",stress(coordinator.getStressSampleProvider(device,session),fromMs,toMs,zone));
            out.put("spo2",spo2(coordinator.getSpo2SampleProvider(device,session),fromMs,toMs,zone));
            out.put("sleep_score",sleepScores(coordinator.getSleepScoreProvider(device,session),fromMs,toMs,zone));
        }catch(Exception failed){
            out.put("error","Não consegui ler o banco do Gadgetbridge: "+failed.getClass().getSimpleName());
        }
        return out;
    }

    /** Per-minute samples: steps, heart rate and sleep stages, with totals
     * and hourly (≤48 h) or daily buckets in the phone's time zone. */
    private static Object activity(SampleProvider<? extends ActivitySample> provider,long fromMs,long toMs,int hours,ZoneId zone)throws JSONException{
        if(provider==null)return JSONObject.NULL;
        List<? extends ActivitySample> samples=provider.getAllActivitySamples((int)(fromMs/1000),(int)(toMs/1000));
        HeartRateUtils hr=HeartRateUtils.getInstance();
        boolean hourly=hours<=48;
        Map<Long,Bucket> buckets=new TreeMap<>();
        Bucket total=new Bucket();
        int light=0,deep=0,rem=0,awake=0,notWorn=0;
        long last=0;
        for(ActivitySample s:samples){
            long ms=s.getTimestamp()*1000L;
            ZonedDateTime at=Instant.ofEpochMilli(ms).atZone(zone).truncatedTo(hourly?ChronoUnit.HOURS:ChronoUnit.DAYS);
            Bucket b=buckets.computeIfAbsent(at.toInstant().toEpochMilli(),k->new Bucket());
            int steps=s.getSteps();
            if(steps>0){b.steps+=steps;total.steps+=steps;}
            int bpm=s.getHeartRate();
            if(hr.isValidHeartRateValue(bpm)){b.heart(bpm);total.heart(bpm);}
            ActivityKind kind=s.getKind();
            if(kind==ActivityKind.LIGHT_SLEEP)light++;
            else if(kind==ActivityKind.DEEP_SLEEP)deep++;
            else if(kind==ActivityKind.REM_SLEEP)rem++;
            else if(kind==ActivityKind.AWAKE_SLEEP)awake++;
            else if(kind==ActivityKind.NOT_WORN)notWorn++;
            if(ActivityKind.isSleep(kind))b.sleep++;
            last=Math.max(last,ms);
        }
        JSONArray series=new JSONArray();
        for(Map.Entry<Long,Bucket> e:buckets.entrySet())
            series.put(e.getValue().json(new JSONObject().put(hourly?"hour":"day",format(e.getKey(),zone))));
        return new JSONObject()
            .put("samples",samples.size())
            .put("latest_sample",last==0?JSONObject.NULL:format(last,zone))
            .put("steps",total.steps)
            .put("heart_rate",total.heartJson())
            .put("sleep_minutes",new JSONObject().put("light",light).put("deep",deep).put("rem",rem).put("awake",awake)
                .put("total_asleep",light+deep+rem))
            .put("not_worn_minutes",notWorn)
            .put(hourly?"hourly":"daily",series);
    }

    private static Object heartRates(TimeSampleProvider<? extends HeartRateSample> provider,long fromMs,long toMs,ZoneId zone)throws JSONException{
        if(provider==null)return JSONObject.NULL;
        JSONArray list=new JSONArray();
        for(HeartRateSample s:provider.getAllSamples(fromMs,toMs))
            if(s.getHeartRate()>0)list.put(new JSONObject().put("at",format(s.getTimestamp(),zone)).put("bpm",s.getHeartRate()));
        return list;
    }

    private static Object stress(TimeSampleProvider<? extends StressSample> provider,long fromMs,long toMs,ZoneId zone)throws JSONException{
        if(provider==null)return JSONObject.NULL;
        Stats stats=new Stats();
        for(StressSample s:provider.getAllSamples(fromMs,toMs))if(s.getStress()>0)stats.add(s.getStress(),s.getTimestamp());
        return stats.json(zone);
    }

    private static Object spo2(TimeSampleProvider<? extends Spo2Sample> provider,long fromMs,long toMs,ZoneId zone)throws JSONException{
        if(provider==null)return JSONObject.NULL;
        Stats stats=new Stats();
        for(Spo2Sample s:provider.getAllSamples(fromMs,toMs))if(s.getSpo2()>0&&s.getSpo2()<=100)stats.add(s.getSpo2(),s.getTimestamp());
        return stats.json(zone);
    }

    private static Object sleepScores(TimeSampleProvider<? extends SleepScoreSample> provider,long fromMs,long toMs,ZoneId zone)throws JSONException{
        if(provider==null)return JSONObject.NULL;
        JSONArray list=new JSONArray();
        for(SleepScoreSample s:provider.getAllSamples(fromMs,toMs))
            if(s.getSleepScore()>0)list.put(new JSONObject().put("at",format(s.getTimestamp(),zone)).put("score",s.getSleepScore()));
        return list;
    }

    private static String format(long ms,ZoneId zone){return ISO.format(Instant.ofEpochMilli(ms).atZone(zone));}

    private static final class Bucket{
        int steps,sleep,hrMin=Integer.MAX_VALUE,hrMax,hrCount;long hrSum;
        void heart(int bpm){hrMin=Math.min(hrMin,bpm);hrMax=Math.max(hrMax,bpm);hrSum+=bpm;hrCount++;}
        Object heartJson()throws JSONException{
            if(hrCount==0)return JSONObject.NULL;
            return new JSONObject().put("min",hrMin).put("avg",Math.round((double)hrSum/hrCount)).put("max",hrMax).put("samples",hrCount);
        }
        JSONObject json(JSONObject o)throws JSONException{
            o.put("steps",steps).put("sleep_minutes",sleep);
            if(hrCount>0)o.put("hr_avg",Math.round((double)hrSum/hrCount)).put("hr_max",hrMax);
            return o;
        }
    }

    private static final class Stats{
        int min=Integer.MAX_VALUE,max,count,latest;long sum,latestAt;
        void add(int v,long at){min=Math.min(min,v);max=Math.max(max,v);sum+=v;count++;if(at>=latestAt){latestAt=at;latest=v;}}
        Object json(ZoneId zone)throws JSONException{
            if(count==0)return JSONObject.NULL;
            return new JSONObject().put("min",min).put("avg",Math.round((double)sum/count)).put("max",max)
                .put("samples",count).put("latest",latest).put("latest_at",format(latestAt,zone));
        }
    }
}
