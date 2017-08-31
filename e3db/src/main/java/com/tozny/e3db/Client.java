package com.tozny.e3db;

import android.os.Handler;
import android.os.Looper;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tozny.e3db.crypto.AndroidCrypto;
import com.tozny.e3db.crypto.KaliumCrypto;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.ByteString;
import retrofit2.Call;
import retrofit2.Retrofit;

public class Client {
  private static final ObjectMapper mapper = new ObjectMapper();
  private static final MediaType APPLICATION_JSON = MediaType.parse("application/json");
  private static final SimpleDateFormat iso8601 = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSSSS");
  private static final MediaType PLAIN_TEXT = MediaType.parse("text/plain");
  private static final Executor backgroundExecutor;
  private static final Executor uiExecutor;
  private static final Crypto crypto;
  private static final String allow = "{\"allow\" : [ { \"read\": {} } ] }";
  private static final String deny = "{\"deny\" : [ { \"read\": {} } ] }";

  static {

    backgroundExecutor = new ThreadPoolExecutor(1,
      Runtime.getRuntime().availableProcessors(),
      30,
      TimeUnit.SECONDS,
      new LinkedBlockingQueue<Runnable>(10),
      new ThreadFactory() {
        private int threadCount = 1;
        @Override
        public Thread newThread(Runnable runnable) {
          final Thread thread = Executors.defaultThreadFactory().newThread(runnable);
          thread.setDaemon(true);
          thread.setName("E3DB background "+ threadCount++);
          return thread;
        }
      });

    boolean isAndroid = false;
    try {
      Class.forName("android.os.Build");
      isAndroid = true;
    } catch (ClassNotFoundException ignored) {
    }

    if (isAndroid) {
      crypto = new AndroidCrypto();
      // Post results to UI thread
      uiExecutor = new Executor() {
        private final Handler handler = new Handler(Looper.getMainLooper());

        @Override
        public void execute(Runnable runnable) {
          handler.post(runnable);
        }
      };
    } else {
      crypto = new KaliumCrypto();
      // Post results to current thread (whatever that is)
      uiExecutor = new Executor() {
        @Override
        public void execute(Runnable runnable) {
          runnable.run();
        }
      };
    }
  }

  private final String apiKey;
  private final String apiSecret;

  private final UUID clientId;
  private final byte[] privateKey;
  private final StorageAPI storageClient;
  private final ShareAPI shareClient;

  Client(String apiKey, String apiSecret, UUID clientId, URI host, byte[] privateKey) {
    this.apiKey = apiKey;
    this.apiSecret = apiSecret;
    this.clientId = clientId;
    this.privateKey = privateKey;

    Retrofit build = new Retrofit.Builder()
      .callbackExecutor(this.uiExecutor)
      .client(new OkHttpClient.Builder()
        .addInterceptor(new TokenInterceptor(apiKey, apiSecret, host))
        .build())
      .baseUrl(host.resolve("/").toString())
      .build();

    storageClient = build.create(StorageAPI.class);
    shareClient = build.create(ShareAPI.class);
  }

  private static class CC implements ClientCredentials {
    private final String apiKey;
    private final String apiSecret;
    private final UUID clientId;

    public CC(String apiKey, String apiSecret, UUID clientId) {
      this.apiKey = apiKey;
      this.apiSecret = apiSecret;
      this.clientId = clientId;
    }

    @Override
    public String apiKey() {
      return apiKey;
    }

    @Override
    public String apiSecret() {
      return apiSecret;
    }

    @Override
    public UUID clientId() {
      return clientId;
    }
  }

  private void onBackground(Runnable runnable) {
    backgroundExecutor.execute(runnable);
  }

  private static <R> void executeError(Executor executor, final ResultHandler<R> handler, final Throwable e) {
    if (handler != null)
      executor.execute(new Runnable() {
        @Override
        public void run() {
          handler.handle(new ErrorResult<R>(e));
        }
      });
  }

  private static <R> void executeValue(Executor executor, final ResultHandler<R> handler, final R value) {
    if (handler != null)
      executor.execute(new Runnable() {
        @Override
        public void run() {
          handler.handle(new ValueResult<R>(value));
        }
      });
  }

  /**
   * Obtains a Tozny JWT, if necessary, and adds it to every request.
   */

  private <R> void uiError(final ResultHandler<R> handleError, final Throwable e) {
    if (handleError != null)
      uiExecutor.execute(new Runnable() {
        @Override
        public void run() {
          handleError.handle(new ErrorResult<R>(e));
        }
      });
  }

  private <R> void uiValue(final ResultHandler<R> handleResult, final R r) {
    if (handleResult != null)
      uiExecutor.execute(new Runnable() {
        @Override
        public void run() {
          handleResult.handle(new ValueResult<R>(r));
        }
      });
  }
  private static class ER {
    public final CipherWithNonce edk; // encrypted data key

    public final CipherWithNonce ef; // encrypted field
    public ER(String quad) {
      int split = quad.indexOf(".", quad.indexOf(".") + 1);
      edk = CipherWithNonce.decode(quad.substring(0, split));
      ef = CipherWithNonce.decode(quad.substring(split + 1));
    }

  }

  private static R makeR(byte[] accessKey, JsonNode rawMeta, JsonNode fields, Crypto crypto) throws ParseException, UnsupportedEncodingException {
    UUID recordId = UUID.fromString(rawMeta.get("record_id").asText());
    UUID writerId = UUID.fromString(rawMeta.get("writer_id").asText());
    Date created = iso8601.parse(rawMeta.get("created").asText());
    Date lastModified = iso8601.parse(rawMeta.get("last_modified").asText());
    String version = rawMeta.get("version").asText();
    String type = rawMeta.get("type").asText();
    JsonNode plain = rawMeta.has("plain") ? rawMeta.get("plain") : mapper.createObjectNode();
    Map<String, String> results = decryptObject(accessKey, fields, crypto);
    RecordMeta meta = new M(recordId, writerId, version, created, lastModified, type, plain);
    return new R(results, meta);
  }
  private static class M implements RecordMeta {
    private final UUID recordId;
    private final UUID writerId;
    private final String version;
    private final Date created;
    private final Date lastModified;
    private final String type;

    private final JsonNode plain;

    private M(UUID recordId, UUID writerId, String version, Date created, Date lastModified, String type, JsonNode plain) {
      this.recordId = recordId;
      this.writerId = writerId;
      this.version = version;
      this.created = created;
      this.lastModified = lastModified;
      this.type = type;
      this.plain = plain;
    }

    public UUID recordId() {
      return recordId;
    }

    public UUID writerId() {
      return writerId;
    }

    public String version() {
      return version;
    }

    public Date created() {
      return created;
    }

    public Date lastModified() {
      return lastModified;
    }

    public String type() {
      return type;
    }
    public String plain() {
      try {
        return mapper.writeValueAsString(plain);
      } catch (JsonProcessingException e) {
        throw new RuntimeException(e);
      }
    }

  }

  private static class R implements Record {
    private final Map<String, String> data;

    private final RecordMeta meta;

    public R(Map<String, String> data, RecordMeta meta) {
      this.data = data;
      this.meta = meta;
    }

    @Override
    public RecordMeta meta() {
      return meta;
    }

    @Override
    public Map<String, String> data() {
      return data;
    }
    }

  private static class QR implements QueryResponse {
    private final ArrayList<Record> records;

    private final long lastIdx;

    private QR(ArrayList<Record> records, long lastIdx) {
      this.records = records;
      this.lastIdx = lastIdx;
    }

    @Override
    public List<Record> records() {
      return records;
    }
    @Override
    public long last() {
      return lastIdx;
    }
  }

  private QueryResponse doSearchRequest(QueryParams params) throws IOException, E3DBUnauthorizedException, ParseException {
    Map<String, Object> searchRequest = new HashMap<>();
    searchRequest.put("after_index", params.after);
    searchRequest.put("count", params.count);
    searchRequest.put("include_data", true);
    searchRequest.put("include_all_writers", true);
    if (params.types != null)
      searchRequest.put("content_types", params.types.toArray());
    String content = mapper.writeValueAsString(searchRequest);
    RequestBody queryRequest = RequestBody.create(APPLICATION_JSON, content);
    retrofit2.Response<ResponseBody> execute = storageClient.query(queryRequest).execute();
    if (execute.code() != 200)
      throw new E3DBUnauthorizedException(execute.code(), execute.message());

    JsonNode results = mapper.readTree(execute.body().string());

    JsonNode currPage = results.get("results");
    long nextIdx = results.get("last_index").asLong();

    ArrayList<Record> records = new ArrayList<>(currPage.size());
    for (int currRow = 0; currRow < currPage.size(); currRow++) {
      JsonNode queryRecord = currPage.get(currRow);
      records.add(makeR(crypto.decryptBox(
        CipherWithNonce.decode(queryRecord.get("access_key").get("eak").asText()),
        ByteString.decodeBase64(queryRecord.get("access_key").get("authorizer_public_key").get("curve25519").asText()).toByteArray(),
        privateKey
        ),
        queryRecord.get("meta"),
        queryRecord.get("record_data"),
        crypto)
      );
    }

    return new QR(records, nextIdx);
  }

  private static Map<String, String> encryptObject(byte[] accessKey, Map<String, String> fields, Crypto crypto) throws UnsupportedEncodingException {
    Map<String, String> encFields = new HashMap<>();
    for (Map.Entry<String, String> entry : fields.entrySet()) {
      byte[] dk = crypto.newSecretKey();

      String encField = new StringBuilder(crypto.encryptSecretBox(dk, accessKey).toMessage()).append(".")
        .append(crypto.encryptSecretBox(entry.getValue().getBytes("UTF-8"), dk).toMessage()).toString();
      encFields.put(entry.getKey(), encField);
    }
    return encFields;
  }

  private static Map<String, String> decryptObject(byte[] accessKey, JsonNode record, Crypto crypto) throws UnsupportedEncodingException {
    Map<String, String> decryptedFields = new HashMap<>();
    Iterator<String> keys = record.fieldNames();
    while (keys.hasNext()) {
      String key = keys.next();
      ER er = new ER(record.get(key).asText());
      byte[] dk = crypto.decryptSecretBox(er.edk, accessKey);
      String value = new String(crypto.decryptSecretBox(er.ef, dk), "UTF-8");
      decryptedFields.put(key, value);
    }
    return decryptedFields;
  }

  private static class TokenInterceptor implements Interceptor {
    private final URI host;
    private final AuthAPI authClient;

    private final String basic;
    private String token = null;

    private Date replaceAfter = new Date(0L);

    private TokenInterceptor(String apiKey, String apiSecret, URI host) {
      this.host = host;
      try {
        this.basic = new StringBuffer("Basic ").append(ByteString.of(new StringBuffer(apiKey).append(":").append(apiSecret).toString().getBytes("UTF-8")).base64()).toString();
      } catch (UnsupportedEncodingException e) {
        throw new RuntimeException(e);
      }
      authClient = new Retrofit.Builder()
        .baseUrl(host.resolve("/").toString())
        .build()
        .create(AuthAPI.class);
    }
    @Override
    public Response intercept(Chain chain) throws IOException {
      Request req = chain.request();
      if (req.header("Authorization") != null) {
        return chain.proceed(req);
      } else {
        if (replaceAfter.before(new Date())) {
          retrofit2.Response<ResponseBody> response = authClient.getToken(basic, RequestBody.create(PLAIN_TEXT, "grant_type=client_credentials")).execute();
          if (response.code() != 200)
            throw new IOException("Unable to renew token.");

          JsonNode token = mapper.readTree(response.body().string());
          Calendar c = Calendar.getInstance();
          c.add(Calendar.SECOND, Math.max(60, Math.min(15 * 60, token.get("expires_in").asInt() - 60)));
          replaceAfter = c.getTime();
          this.token = token.get("access_token").asText();
        }

        return chain.proceed(req.newBuilder().addHeader("Authorization", "Bearer " + token).build());
      }
    }

  }

  private byte[] getOwnAccessKey(String type) throws E3DBException, IOException {
    byte[] ak = getAccessKey(this.clientId, this.clientId, this.clientId, type);
    if (ak == null) {
      // Write new AK
      ak = crypto.newSecretKey();
      setAccessKey(this.clientId, this.clientId, this.clientId, type, crypto.getPublicKey(this.privateKey), ak);
    }

    return ak;
  }

  private void removeAccessKey(UUID writerId, UUID userId, UUID readerId, String type) throws IOException, E3DBException {
    retrofit2.Response<ResponseBody> response = storageClient.deleteAccessKey(writerId.toString(), userId.toString(), readerId.toString(), type).execute();
    if(response.code() == 403) {
      throw new E3DBUnauthorizedException(response.code(), response.message());
    }
    else if(response.code() != 204) {
      throw new E3DBException("HTTP " + response.code() + " " + response.message());
    }
  }

  private byte[] getAccessKey(UUID writerId, UUID userId, UUID readerId, String type) throws E3DBUnauthorizedException, IOException {
    // TODO: cache AKs.
    retrofit2.Response<ResponseBody> response = storageClient.getAccessKey(writerId.toString(), userId.toString(), readerId.toString(), type).execute();
    if (response.code() == 403)
      throw new E3DBUnauthorizedException(response.code(), response.message());
    else if (response.code() == 404) {
      return null;
    } else {
      JsonNode eakResponse = mapper.readTree(response.body().string());
      byte[] ak = crypto.decryptBox(eakResponse.get("eak").asText(),
        crypto.getPublicKey(this.privateKey),
        this.privateKey);
      return ak;
    }
  }

  private void setAccessKey(UUID writerId, UUID userId, UUID readerId, String type, byte[] readerKey, byte[] ak) throws E3DBException, IOException {
    Map<String, String> eak = new HashMap<>();
    eak.put("eak", crypto.encryptBox(ak, readerKey, this.privateKey).toMessage());

    RequestBody body = RequestBody.create(APPLICATION_JSON, mapper.writeValueAsString(eak));
    retrofit2.Response<ResponseBody> response = storageClient.putAccessKey(writerId.toString(), userId.toString(), readerId.toString(), type, body).execute();
    if (response.code() == 403) {
      throw new E3DBUnauthorizedException(response.code(), response.message());
    } else if (response.code() != 201) {
      throw new E3DBException("HTTP " + response.code() + " " + response.message());
    }
  }

  private JsonNode doLookup(String readerEmail) throws IOException, E3DBException {
    final retrofit2.Response<ResponseBody> infoResponse = shareClient.lookupClient(readerEmail).execute();
    if(infoResponse.code() == 404) {
      throw new E3DBClientNotFoundException(readerEmail);
    }
    else if(infoResponse.code() != 200) {
      throw new E3DBException("HTTP " + infoResponse.code() + " " + infoResponse.message());
    }

    return mapper.readTree(infoResponse.body().string());
  }

  private void doShare(UUID readerId, String type, ResultHandler<Void> handleResult) throws IOException, E3DBException {
    if(getAccessKey(clientId, clientId, readerId, type) == null) {
      final retrofit2.Response<ResponseBody> clientInfo = shareClient.lookupClient(readerId).execute();
      if (clientInfo.code() == 404) {
        uiError(handleResult, new E3DBClientNotFoundException(readerId.toString()));
        return;
      } else if (clientInfo.code() != 200) {
        uiError(handleResult, new E3DBException("HTTP " + clientInfo.code() + " " + clientInfo.message()));
        return;
      }

      byte[] ak = getOwnAccessKey(type);
      JsonNode info = mapper.readTree(clientInfo.body().string());
      byte[] readerKey = ByteString.decodeBase64(info.get("public_key").get("curve25519").asText()).toByteArray();
      setAccessKey(this.clientId, this.clientId, readerId, type, readerKey, ak);
    }

    retrofit2.Response<ResponseBody> shareResponse = shareClient.putPolicy(
      clientId.toString(),
      clientId.toString(),
      readerId.toString(),
      RequestBody.create(APPLICATION_JSON, allow)).execute();

    if(shareResponse.code() != 201) {
      uiError(handleResult, new E3DBException("HTTP " + shareResponse.code() + " " + shareResponse.message()));
    }
    else
      uiValue(handleResult, null);
  }

  private void doRevoke(UUID readerId, String type, ResultHandler<Void> handleResult) throws IOException, E3DBException {
    removeAccessKey(clientId, clientId, readerId, type);
    retrofit2.Response<ResponseBody> shareResponse = shareClient.putPolicy(
      clientId.toString(),
      clientId.toString(),
      readerId.toString(),
      RequestBody.create(APPLICATION_JSON, deny)).execute();

    if(shareResponse.code() != 201) {
      uiError(handleResult, new E3DBException("HTTP " + shareResponse.code() + " " + shareResponse.message()));
    }
    else
      uiValue(handleResult, null);
  }

  /**
   * Generates a private key that can be used for Curve25519
   * public-key encryption.
   *
   * The associated public key be retrieve using {@link getPublicKey}.
   *
   * The returned value represents the key as a Base64URL-encoded string.
   * @return
   */
  public static String newPrivateKey() {
    byte [] key = crypto.newPrivateKey();
    return ByteString.of(key).base64Url();
  }

  /**
   * Gets the public key calculated from the given private key.
   *
   * The private key must be a Base64URL-encoded string.
   *
   * The returned value represents the key as a Base64URL-encoded string.
   * @param privateKey
   * @return
   */
  public static String getPublicKey(String privateKey) {
    ByteString arr = ByteString.decodeBase64(privateKey);
    if(arr == null)
      throw new IllegalArgumentException("privateKey could not be decoded.");

    return ByteString.of(crypto.getPublicKey(arr.toByteArray())).base64Url();
  }

  public UUID clientId() {
    return clientId;
  }

  public static void register(final String token, final String clientName, final String host, final ResultHandler<Config> handleResult) {
    final byte[] privateKey = crypto.newPrivateKey();
    final String publicKey = ByteString.of(crypto.getPublicKey(privateKey)).base64Url();
    register(token, clientName, publicKey, host, new ResultHandler<ClientCredentials>() {
      @Override
      public void handle(Result<ClientCredentials> r) {
        if (r.isError()) {
          executeError(uiExecutor, handleResult, r.asError().other());
        }
        final ClientCredentials credentials = r.asValue();
        Config info = new Config(credentials.apiKey(), credentials.apiSecret(), credentials.clientId(), clientName, host, ByteString.of(privateKey).base64Url(),
          publicKey);
        executeValue(uiExecutor, handleResult, info);
      }
    });
  }

  public static void register(final String token, final String clientName, final String publicKey, String host, final ResultHandler<ClientCredentials> handleResult) {
    final RegisterAPI registerClient = new Retrofit.Builder()
      .callbackExecutor(uiExecutor)
      .client(new OkHttpClient.Builder()
        .build())
      .baseUrl(URI.create(host).resolve("/").toString())
      .build().create(RegisterAPI.class);

    backgroundExecutor.execute(new Runnable() {
      @Override
      public void run() {
        try {
          Map<String, String> publicKeyInfo = new HashMap<>();
          publicKeyInfo.put("curve25519", publicKey);

          Map<String, Object> clientInfo = new HashMap<>();
          clientInfo.put("name", clientName);
          clientInfo.put("public_key", publicKeyInfo);

          Map<String, Object> registerInfo = new HashMap<>();
          registerInfo.put("token", token);
          registerInfo.put("client", clientInfo);

          final retrofit2.Response<ResponseBody> response = registerClient.register(RequestBody.create(APPLICATION_JSON, mapper.writeValueAsString(registerInfo))).execute();
          if (response.code() != 201) {
            executeError(uiExecutor, handleResult, new E3DBException("HTTP " + response.code() + " " + response.message()));
          } else {
            final String doc = response.body().string();
            JsonNode creds = mapper.readTree(doc);
            final String apiKey = creds.get("api_key_id").asText();
            final String apiSecret = creds.get("api_secret").asText();
            final UUID clientId = UUID.fromString(creds.get("client_id").asText());
            final ClientCredentials clientCredentials = new CC(apiKey, apiSecret, clientId);
            executeValue(uiExecutor, handleResult, clientCredentials);
          }
        } catch (final Throwable e) {
          executeError(uiExecutor, handleResult, e);
        }
      }
    });
  }

  /**
   * Write a new record.
   *
   * @param type Describes the type of the record (e.g., "contact_info", "credit_card", etc.).
   * @param fields Values to encrypt and store.
   * @param plain A JSON document that will be stored as plaintext (not encrypted) alongside
   *              the record. If null, an empty JSON document is assumed.
   * @param handleResult
   * @return
   */
  public void write(final String type, final RecordData fields, final Map<String, String> plain, final ResultHandler<Record> handleResult) {
    onBackground(new Runnable() {
      @Override
      public void run() {
        try {
          if (fields == null) {
            uiError(handleResult, new IllegalArgumentException("data null"));
          }
          if (type == null || type.trim().length() == 0) {
            uiError(handleResult, new IllegalArgumentException("type null"));
          }
          final byte[] ownAK = getOwnAccessKey(type);
          Map<String, String> encFields = encryptObject(ownAK, fields.getCleartext(), crypto);

          Map<String, Object> meta = new HashMap<>();
          meta.put("writer_id", clientId.toString());
          meta.put("user_id", clientId.toString());
          meta.put("type", type.trim());

          if (plain != null)
            meta.put("plain", plain);

          Map<String, Object> record = new HashMap<>();
          record.put("meta", meta);
          record.put("data", encFields);

          String content = mapper.writeValueAsString(record);
          final retrofit2.Response<ResponseBody> response = storageClient.writeRecord(RequestBody.create(APPLICATION_JSON, content)).execute();
          if (response.code() == 403) {
            uiError(handleResult, new E3DBUnauthorizedException(response.code(), response.message()));
          } else if (response.code() != 201) {
            uiError(handleResult, new E3DBException("HTTP " + response.code() + " " + response.message()));
          } else {
            JsonNode result = mapper.readTree(response.body().string());
            uiValue(handleResult, makeR(ownAK, result.get("meta"), result.get("data"), crypto));
          }
        } catch (final Throwable e) {
          uiError(handleResult, e);
        }
      }
    });
  }

  /**
   * Replace the given record with new data and plaintext. All existing data and plaintext
   * will be overwritten.
   *
   * @param recordId
   * @param version
   * @param fields
   * @param plain
   * @param handleResult
   * @param type
   * @return
   */
  public void update(final UUID recordId, final String version, final RecordData fields, final Map<String, String> plain, final String type, final ResultHandler<Record> handleResult) {
    onBackground(new Runnable() {
      @Override
      public void run() {
        try {
          final byte[] ownAK = getOwnAccessKey(type);
          Map<String, String> encFields = encryptObject(ownAK, fields.getCleartext(), crypto);

          Map<String, Object> meta = new HashMap<>();
          meta.put("writer_id", clientId.toString());
          meta.put("user_id", clientId.toString());
          meta.put("type", type.trim());

          if (plain != null)
            meta.put("plain", plain);

          Map<String, Object> fields = new HashMap<>();
          fields.put("meta", meta);
          fields.put("data", encFields);

          retrofit2.Response<ResponseBody> response = storageClient.updateRecord(recordId.toString(), version, RequestBody.create(APPLICATION_JSON, mapper.writeValueAsString(fields))).execute();
          if (response.code() == 409) {
            uiError(handleResult, new E3DBVersionException(recordId, version));
          } else if (response.code() == 403) {
            uiError(handleResult, new E3DBUnauthorizedException(response.code(), response.message()));
          } else if (response.code() != 200) {
            uiError(handleResult, new E3DBException("HTTP " + response.code() + " " + response.message()));
          } else {
            JsonNode result = mapper.readTree(response.body().string());
            uiValue(handleResult, makeR(ownAK, result.get("meta"), result.get("data"), crypto));
          }
        } catch (Throwable e) {
          uiError(handleResult, e);
        }
      }
    });
  }

  public void delete(final UUID recordId, final String version, final ResultHandler<Void> handleResult) {
    onBackground(new Runnable() {
      @Override
      public void run() {
        try {
          retrofit2.Response<ResponseBody> response = storageClient.deleteRecord(recordId.toString(), version.toString()).execute();
          if (response.code() == 409) {
            uiError(handleResult, new E3DBVersionException(recordId, version));
          } else if (response.code() == 403) {
            uiError(handleResult, new E3DBUnauthorizedException(response.code(), response.message()));
          } else if (response.code() != 204) {
            uiError(handleResult, new E3DBException("HTTP " + response.code() + " " + response.message()));
          } else {
            uiValue(handleResult, null);
          }
        } catch (Throwable e) {
          uiError(handleResult, e);
        }
      }
    });
  }

  public void read(final UUID recordId, final ResultHandler<Record> handleResult) {
    onBackground(new Runnable() {
      @Override
      public void run() {
        try {
          final retrofit2.Response<ResponseBody> response = storageClient.getRecord(recordId.toString()).execute();
          if (response.code() != 200) {
            if (response.code() == 404)
              uiError(handleResult, new E3DBNotFoundException(recordId));
            else
              uiError(handleResult, new E3DBUnauthorizedException(response.code(), response.message()));
            return;
          }

          final JsonNode result = mapper.readTree(response.body().string());
          final JsonNode meta = result.get("meta");
          final byte[] key = getAccessKey(UUID.fromString(meta.get("writer_id").asText()),
            UUID.fromString(meta.get("user_id").asText()),
            clientId, meta.get("type").asText());
          try {
            uiValue(handleResult, makeR(key, meta, result.get("data"), crypto));
          } catch (ParseException e) {
            uiError(handleResult, e);
          } catch (UnsupportedEncodingException e) {
            uiError(handleResult, e);
          }
        } catch (final Throwable e) {
          uiError(handleResult, e);
        }
      }
    });
  }

  public void query(final QueryParams params, final ResultHandler<QueryResponse> handleResult) {
    onBackground(new Runnable() {
      @Override
      public void run() {
        try {
          final QueryResponse queryResponse = doSearchRequest(params);
          uiValue(handleResult, queryResponse);
        } catch (final E3DBException e) {
          uiError(handleResult, e);
        } catch (final Throwable e) {
          uiError(handleResult, e);
        }
      }
    });
  }

  public void share(final String type, final String readerEmail, final ResultHandler<Void> handleResult) {
    onBackground(new Runnable() {
      @Override
      public void run() {
        try {
          JsonNode info = doLookup(readerEmail);
          doShare(UUID.fromString(info.get("client_id").asText()), type, handleResult);
        } catch (Throwable e) {
          uiError(handleResult, e);
        }
      }
    });
  }

  public void share(final String type, final UUID readerId, final ResultHandler<Void> handleResult) {
    onBackground(new Runnable() {
      public void run() {
        try {
          doShare(readerId, type, handleResult);
        }
        catch(Throwable e) {
          uiError(handleResult, e);
        }
      }
    });
  }

  public void revoke(final String type, final String readerEmail, final ResultHandler<Void> handleResult) {
    onBackground(new Runnable() {
      @Override
      public void run() {
        try {
          JsonNode info = doLookup(readerEmail);
          doRevoke(UUID.fromString(info.get("client_id").asText()), type, handleResult);
        } catch (Throwable e) {
          uiError(handleResult, e);
        }
      }
    });
  }

  public void revoke(final String type, final UUID readerId, final ResultHandler<Void> handleResult) {
    onBackground(new Runnable() {
      public void run() {
        try {
          doRevoke(readerId, type, handleResult);
        } catch (Throwable e) {
          uiError(handleResult, e);
        }
      }
    });
  }
}
