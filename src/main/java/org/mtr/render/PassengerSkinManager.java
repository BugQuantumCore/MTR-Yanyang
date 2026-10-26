package org.mtr.render;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.client.resources.PlayerSkin;
import net.minecraft.resources.ResourceLocation;
import org.mtr.MTR;
import org.mtr.core.tool.Utilities;
import org.mtr.resource.ResourceManagerHelper;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Manages the skin pool used by AI passengers.
 *
 * <p>Three tiers of skins are provided:</p>
 * <ul>
 *   <li><b>Offline default pool</b> — the 9 vanilla default skins, deterministically assigned
 *       per passenger id. Always available, no network access.</li>
 *   <li><b>Bundled premium account pool (default, ON)</b> — a curated list of genuine Mojang
 *       premium accounts bundled as {@code assets/mtr/passenger_skins.json} (every name + UUID
 *       pair was verified live against the Mojang APIs at bundling time). Skins are fetched
 *       asynchronously from {@code sessionserver.mojang.com} <b>by immutable UUID</b> —
 *       rename-proof and one request per account — with a 4-worker fetch pool sharing a single
 *       {@link HttpClient}. Every AI passenger deterministically gets one account, so each
 *       passenger wears a real premium-account skin.</li>
 *   <li><b>Configured skins (optional override)</b> — user-provided names via
 *       {@code config/mtr_passenger_skins.json}, fetched from Mojang or a
 *       CustomSkinLoader-compatible site; the bundled pool is skipped while a non-empty name
 *       list is configured.</li>
 * </ul>
 *
 * <p>Published as a fixed-size slot pool: slot count never changes while a pool is live, each
 * passenger deterministically hashes to one slot, and each slot starts on a vanilla default and
 * is <b>upgraded in place</b> (copy-on-write array publication) when its account's skin has been
 * fetched — so a passenger never hops skins as the fetches land. The generation counter aborts
 * in-flight fetches across reloads; a per-account failure backoff avoids hammering the Mojang
 * APIs when offline; downloaded skins are cached on disk (12 h TTL by default) and a stale
 * cache entry is preferred over no skin at all.</p>
 *
 * <p>Skin textures are uploaded on the render thread; {@link #getSkin(long)} is lock-free and
 * safe to call from the render thread. Legacy 64x32 skins are converted to 64x64 with the exact
 * vanilla recipe (see {@link #convertLegacySkin(NativeImage)}).</p>
 */
public final class PassengerSkinManager {

	private static final int MAX_ONLINE_NAMES = 64;
	private static final int HTTP_TIMEOUT_SECONDS = 10;
	private static final long ONLINE_RETRY_INTERVAL = 10 * Utilities.MILLIS_PER_MINUTE;
	private static final long DEFAULT_REFRESH_MILLIS = 12L * Utilities.MILLIS_PER_HOUR;
	private static final int FETCH_WORKER_COUNT = 4;
	/**
	 * Minimum spacing between sessionserver.mojang.com requests, shared by all fetch workers
	 * (the API is rate-limited and politely used).
	 */
	private static final long PROFILE_REQUEST_SPACING_MILLIS = 600;
	private static final ResourceLocation BUNDLED_ACCOUNTS_LOCATION = ResourceLocation.fromNamespaceAndPath(MTR.MOD_ID, "passenger_skins.json");

	private static final long GOLDEN_RATIO_FRACTION = 0x9E3779B97F4A7C15L;
	private static final int ALPHA_OPAQUE = 0xFF000000;

	private static final Path configPath;
	private static final Path cacheDir;

	private static final Object INIT_LOCK = new Object();
	private static final Object POOL_WRITE_LOCK = new Object();
	private static final Object HTTP_CLIENT_LOCK = new Object();
	private static final Object PROFILE_REQUEST_LOCK = new Object();

	private static volatile PlayerSkin[] skinPool;
	private static volatile boolean onlineSkinsEnabled = true;
	private static volatile List<SkinTarget> fetchTargets = List.of();

	private static final AtomicBoolean fetchStarted = new AtomicBoolean(false);
	private static final AtomicInteger generation = new AtomicInteger();
	private static final AtomicLong lastProfileRequestTime = new AtomicLong();
	private static final ConcurrentHashMap<String, Long> failedKeys = new ConcurrentHashMap<>();
	/**
	 * Texture keys ({@code <account>_<model>}) of skins whose texture is already registered in
	 * the {@link net.minecraft.client.renderer.texture.TextureManager}, so redundant parses and
	 * uploads are skipped while a session is alive.
	 */
	private static final ConcurrentHashMap<String, PlayerSkin> registeredSkins = new ConcurrentHashMap<>();

	// Configuration, reloaded on init
	private static volatile List<String> configuredNames = List.of();
	private static volatile String source = "mojang";
	private static volatile String cslUrl = "https://littleskin.cn";
	private static volatile long refreshMillis = DEFAULT_REFRESH_MILLIS;

	private static volatile ExecutorService fetchExecutor;
	private static volatile HttpClient sharedHttpClient;
	private static volatile List<SkinTarget> bundledAccounts;
	private static volatile boolean bundledAccountsLoaded;

	static {
		final Path gameDir = Minecraft.getInstance().gameDirectory.toPath();
		configPath = gameDir.resolve("config/mtr_passenger_skins.json");
		cacheDir = gameDir.resolve("cache/mtr/passenger-skins");
	}

	private PassengerSkinManager() {
	}

	/**
	 * Loads the configuration (auto-creating it with documented defaults on first run).
	 * Called from the client init.
	 */
	public static void init() {
		readConfig();
	}

	/**
	 * @return the skin for a passenger, deterministically assigned from the current pool
	 */
	public static PlayerSkin getSkin(long passengerId) {
		ensureLoaded();
		final PlayerSkin[] pool = skinPool;
		return pool[(int) Math.floorMod(passengerId * GOLDEN_RATIO_FRACTION, pool.length)];
	}

	/**
	 * Re-reads the configuration file, rebuilds the slot pool and restarts the asynchronous
	 * online skin fetch (aborting any in-flight one).
	 */
	public static void reload() {
		readConfig();
		generation.incrementAndGet();
		skinPool = null;
		fetchStarted.set(false);
	}

	private static void ensureLoaded() {
		if (skinPool == null) {
			synchronized (INIT_LOCK) {
				if (skinPool == null) {
					initializePool();
				}
			}
		}
		if (onlineSkinsEnabled && fetchStarted.compareAndSet(false, true)) {
			startFetch();
		}
	}

	/**
	 * Builds the fixed-size slot pool: each slot starts on a vanilla default (round-robin over
	 * the 9 defaults) and is upgraded in place when its assigned account's skin arrives.
	 */
	private static void initializePool() {
		final PlayerSkin[] defaults = buildDefaultPool();
		final List<SkinTarget> targets = onlineSkinsEnabled ? buildTargets() : List.of();
		fetchTargets = targets;
		if (targets.isEmpty()) {
			skinPool = defaults;
			return;
		}
		final PlayerSkin[] slots = new PlayerSkin[targets.size()];
		for (int i = 0; i < slots.length; i++) {
			slots[i] = defaults[i % defaults.length];
		}
		skinPool = slots;
	}

	private static void startFetch() {
		final List<SkinTarget> targets = fetchTargets;
		if (targets.isEmpty()) {
			return;
		}
		try {
			Files.createDirectories(cacheDir);
		} catch (Exception e) {
			MTR.LOGGER.warn("Failed to create passenger skin cache directory", e);
			return;
		}
		final int currentGeneration = generation.get();
		for (int i = 0; i < targets.size(); i++) {
			final int slot = i;
			final SkinTarget target = targets.get(i);
			fetchExecutor().submit(() -> fetchTarget(target, slot, currentGeneration));
		}
	}

	private static void fetchTarget(SkinTarget target, int slot, int fetchGeneration) {
		final String key = target.key();
		try {
			if (generation.get() != fetchGeneration) {
				return;
			}
			final Long failedAt = failedKeys.get(key);
			if (failedAt != null && System.currentTimeMillis() - failedAt < ONLINE_RETRY_INTERVAL) {
				return;
			}

			PlayerSkin skin = null;
			try {
				skin = loadSkinForKey(target);
			} catch (Exception e) {
				MTR.LOGGER.warn("Failed to fetch passenger skin for {} (source {})", target.name, source, e);
			}
			if (skin == null) {
				// Offline fallback: a stale cached skin beats no skin at all.
				for (final PlayerSkin.Model model : PlayerSkin.Model.values()) {
					skin = loadFromCache(cacheFile(key, model), key, model, true);
					if (skin != null) {
						break;
					}
				}
			}

			if (skin != null) {
				failedKeys.remove(key);
				final PlayerSkin finalSkin = skin;
				Minecraft.getInstance().execute(() -> upgradeSlot(slot, finalSkin, fetchGeneration));
			} else {
				failedKeys.put(key, System.currentTimeMillis());
			}
		} catch (Exception e) {
			failedKeys.put(key, System.currentTimeMillis());
			MTR.LOGGER.warn("Passenger skin fetch error for {}", target.name, e);
		}
	}

	/**
	 * Replaces one slot's skin in the published pool (copy-on-write, on the render thread).
	 * Aborted when a reload has bumped the generation meanwhile.
	 */
	private static void upgradeSlot(int slot, PlayerSkin skin, int fetchGeneration) {
		if (generation.get() != fetchGeneration) {
			return;
		}
		synchronized (POOL_WRITE_LOCK) {
			final PlayerSkin[] current = skinPool;
			if (current == null || slot < 0 || slot >= current.length) {
				return;
			}
			if (current[slot] == skin) {
				return;
			}
			final PlayerSkin[] updated = current.clone();
			updated[slot] = skin;
			skinPool = updated;
		}
	}

	private static PlayerSkin loadSkinForKey(SkinTarget target) throws Exception {
		final String key = target.key();
		// Fresh disk cache first (this is the common path after the first session).
		for (final PlayerSkin.Model model : PlayerSkin.Model.values()) {
			final PlayerSkin cached = loadFromCache(cacheFile(key, model), key, model, false);
			if (cached != null) {
				return cached;
			}
		}
		if ("csl".equalsIgnoreCase(source)) {
			return fetchFromCsl(target.name);
		}
		return fetchFromMojang(target);
	}

	/**
	 * Loads a cached skin, reusing the already-registered texture when the session already has
	 * it (no parse, no upload).
	 */
	private static PlayerSkin loadFromCache(Path file, String key, PlayerSkin.Model model, boolean allowStale) {
		if (!Files.exists(file)) {
			return null;
		}
		try {
			final boolean stale = System.currentTimeMillis() - Files.getLastModifiedTime(file).toMillis() > refreshMillis;
			if (stale && !allowStale) {
				return null;
			}
			final PlayerSkin existing = registeredSkins.get(textureKey(key, model));
			if (existing != null) {
				return existing;
			}
			return registerSkin(key, Files.readAllBytes(file), model, false);
		} catch (Exception e) {
			MTR.LOGGER.warn("Failed to load cached passenger skin for {}", key, e);
			return null;
		}
	}

	/**
	 * Mojang flow. Bundled accounts carry their immutable UUID, so the profile is fetched
	 * UUID-first (rename-proof, one request instead of two); user-configured names are resolved
	 * to a UUID first, exactly like the classic flow.
	 */
	private static PlayerSkin fetchFromMojang(SkinTarget target) throws Exception {
		String uuid = target.uuid;
		if (uuid == null) {
			awaitProfileRequestSlot();
			final String body = httpGetJson("https://api.mojang.com/users/profiles/minecraft/" + urlEncode(target.name));
			if (body == null) {
				return null;
			}
			uuid = JsonParser.parseString(body).getAsJsonObject().get("id").getAsString();
		}
		awaitProfileRequestSlot();
		final String profile = httpGetJson("https://sessionserver.mojang.com/session/minecraft/profile/" + uuid.replace("-", "") + "?unsigned=false");
		if (profile == null) {
			return null;
		}
		final JsonObject profileJson = JsonParser.parseString(profile).getAsJsonObject();
		for (final JsonElement property : profileJson.getAsJsonArray("properties")) {
			final JsonObject propertyObject = property.getAsJsonObject();
			if ("textures".equals(propertyObject.get("name").getAsString())) {
				final JsonObject textures = JsonParser.parseString(new String(Base64.getDecoder().decode(propertyObject.get("value").getAsString()), StandardCharsets.UTF_8)).getAsJsonObject().getAsJsonObject("textures");
				final JsonObject skin = textures == null ? null : textures.getAsJsonObject("SKIN");
				if (skin != null) {
					final PlayerSkin.Model model = skin.has("metadata") && skin.getAsJsonObject("metadata").has("model") && "slim".equals(skin.getAsJsonObject("metadata").get("model").getAsString()) ? PlayerSkin.Model.SLIM : PlayerSkin.Model.WIDE;
					final byte[] bytes = httpGetBytes(skin.get("url").getAsString());
					writeCache(target.key(), model, bytes);
					return registerSkin(target.key(), bytes, model, true);
				}
			}
		}
		return null;
	}

	/**
	 * CustomSkinLoader API flow: {@code <site>/csl/<name>} -> skin URLs.
	 */
	private static PlayerSkin fetchFromCsl(String name) throws Exception {
		final String response = httpGetJson(cslUrl.replaceAll("/$", "") + "/csl/" + urlEncode(name));
		if (response == null) {
			return null;
		}
		final JsonObject json = JsonParser.parseString(response).getAsJsonObject();
		final JsonObject skins = json.has("skins") ? json.getAsJsonObject("skins") : null;
		if (skins == null) {
			return null;
		}
		// Prefer the slim model when available for extra variety.
		if (skins.has("slim")) {
			final byte[] bytes = httpGetBytes(skins.get("slim").getAsString());
			writeCache(name, PlayerSkin.Model.SLIM, bytes);
			return registerSkin(name, bytes, PlayerSkin.Model.SLIM, true);
		}
		if (skins.has("default")) {
			final byte[] bytes = httpGetBytes(skins.get("default").getAsString());
			writeCache(name, PlayerSkin.Model.WIDE, bytes);
			return registerSkin(name, bytes, PlayerSkin.Model.WIDE, true);
		}
		return null;
	}

	/**
	 * Validates, sanitizes and registers the skin texture. NativeImage parsing and the legacy
	 * conversion run on the calling (worker) thread; the GL upload is scheduled on the render
	 * thread. When {@code force} is set (freshly downloaded bytes) the texture is re-registered
	 * under its stable location, replacing any previous pixels there.
	 */
	private static PlayerSkin registerSkin(String key, byte[] bytes, PlayerSkin.Model model, boolean force) throws Exception {
		final String texKey = textureKey(key, model);
		if (!force) {
			final PlayerSkin existing = registeredSkins.get(texKey);
			if (existing != null) {
				return existing;
			}
		}

		final NativeImage image;
		try (final InputStream inputStream = new ByteArrayInputStream(bytes)) {
			image = NativeImage.read(inputStream);
		}
		if (image == null) {
			return null;
		}
		try {
			final NativeImage sanitized = sanitizeSkin(image, key);
			if (sanitized == null) {
				return null;
			}
			final ResourceLocation textureIdentifier = ResourceLocation.fromNamespaceAndPath(MTR.MOD_ID, "passenger_skin/" + texKey);
			Minecraft.getInstance().execute(() -> {
				try {
					// TextureManager.register closes the texture it replaces.
					Minecraft.getInstance().getTextureManager().register(textureIdentifier, new DynamicTexture(sanitized));
				} catch (Exception e) {
					sanitized.close();
					MTR.LOGGER.warn("Failed to register passenger skin texture for {}", texKey, e);
				}
			});
			final PlayerSkin skin = new PlayerSkin(textureIdentifier, null, null, null, model, false);
			registeredSkins.put(texKey, skin);
			return skin;
		} catch (Exception e) {
			image.close();
			throw e;
		}
	}

	/**
	 * Rejects non-skin dimensions, converts legacy 64x32 skins, and applies vanilla's
	 * opaque-region fixups to every skin (vanilla {@code HttpTexture.processLegacySkin} is
	 * invoked for every player skin and forces the head / upper-body / lower-body regions
	 * opaque regardless of format).
	 */
	private static NativeImage sanitizeSkin(NativeImage image, String key) {
		final int width = image.getWidth();
		final int height = image.getHeight();
		if (width != 64 || (height != 64 && height != 32)) {
			MTR.LOGGER.warn("Passenger skin for {} is {}x{}, expected 64x64 or 64x32; skipping", key, width, height);
			image.close();
			return null;
		}
		if (height == 32) {
			return convertLegacySkin(image);
		}
		setOpaqueRegion(image, 0, 0, 32, 16);
		setOpaqueRegion(image, 0, 16, 64, 32);
		setOpaqueRegion(image, 16, 48, 48, 64);
		return image;
	}

	/**
	 * Converts a legacy 64x32 skin to the modern 64x64 layout using the EXACT vanilla recipe,
	 * verified instruction-for-instruction against the 1.21.1 {@code HttpTexture.processLegacySkin}
	 * and 1.21.4 {@code SkinTextureDownloader.processLegacySkin} bytecode (identical in both):
	 * copy the 64x32 top half into a fresh 64x64 image, blank the bottom half, then the twelve
	 * mirrored {@code copyRect} calls that place the arm / leg / body side textures, followed by
	 * the opaque-region fixups and the Notch transparency hack on the hat area.
	 *
	 * <p>{@code copyRect(xFrom, yFrom, xToDelta, yToDelta, width, height, mirrorX, mirrorY)}.</p>
	 */
	private static NativeImage convertLegacySkin(NativeImage legacyImage) {
		final NativeImage image = new NativeImage(64, 64, true);
		image.copyFrom(legacyImage);
		legacyImage.close();
		image.fillRect(0, 32, 64, 32, 0);
		image.copyRect(4, 16, 16, 32, 4, 4, true, false);
		image.copyRect(8, 16, 16, 32, 4, 4, true, false);
		image.copyRect(0, 20, 24, 32, 4, 12, true, false);
		image.copyRect(4, 20, 16, 32, 4, 12, true, false);
		image.copyRect(8, 20, 8, 32, 4, 12, true, false);
		image.copyRect(12, 20, 16, 32, 4, 12, true, false);
		image.copyRect(44, 16, -8, 32, 4, 4, true, false);
		image.copyRect(48, 16, -8, 32, 4, 4, true, false);
		image.copyRect(40, 20, 0, 32, 4, 12, true, false);
		image.copyRect(44, 20, -8, 32, 4, 12, true, false);
		image.copyRect(48, 20, -16, 32, 4, 12, true, false);
		image.copyRect(52, 20, -8, 32, 4, 12, true, false);
		setOpaqueRegion(image, 0, 0, 32, 16);
		doNotchTransparencyHack(image, 32, 0, 64, 32);
		setOpaqueRegion(image, 0, 16, 64, 32);
		setOpaqueRegion(image, 16, 48, 48, 64);
		return image;
	}

	/**
	 * Forces the alpha byte to opaque across the region, matching vanilla's private helper
	 * ({@code xTo}/{@code yTo} are exclusive, as in vanilla).
	 */
	private static void setOpaqueRegion(NativeImage image, int xFrom, int yFrom, int xTo, int yTo) {
		for (int x = xFrom; x < xTo; x++) {
			for (int y = yFrom; y < yTo; y++) {
//? if >= 1.21.4 {
				image.setPixel(x, y, image.getPixel(x, y) | ALPHA_OPAQUE);
//? } else {
				/*image.setPixelRGBA(x, y, image.getPixelRGBA(x, y) | ALPHA_OPAQUE);
*///? }
			}
		}
	}

	/**
	 * Vanilla {@code HttpTexture.doNotchTransparencyHack}: leaves the region untouched if it
	 * contains any translucent pixel; otherwise blanks the whole region's alpha (a fully-opaque
	 * hat area means the player has no hat, so it is made invisible exactly like vanilla does).
	 */
	private static void doNotchTransparencyHack(NativeImage image, int xFrom, int yFrom, int xTo, int yTo) {
		for (int x = xFrom; x < xTo; x++) {
			for (int y = yFrom; y < yTo; y++) {
				final int alpha;
//? if >= 1.21.4 {
				alpha = image.getPixel(x, y) >>> 24 & 0xFF;
//? } else {
				/*alpha = image.getPixelRGBA(x, y) >>> 24 & 0xFF;
*///? }
				if (alpha < 128) {
					return;
				}
			}
		}
		for (int x = xFrom; x < xTo; x++) {
			for (int y = yFrom; y < yTo; y++) {
//? if >= 1.21.4 {
				image.setPixel(x, y, image.getPixel(x, y) & 0xFFFFFF);
//? } else {
				/*image.setPixelRGBA(x, y, image.getPixelRGBA(x, y) & 0xFFFFFF);
*///? }
			}
		}
	}

	/**
	 * Builds the 9-skin vanilla default pool by picking distinct {@link DefaultPlayerSkin}
	 * entries via deterministic offline UUIDs.
	 */
	private static PlayerSkin[] buildDefaultPool() {
		final List<PlayerSkin> skins = new ArrayList<>();
		for (int i = 0; i < 64 && skins.size() < 9; i++) {
			final UUID uuid = UUID.nameUUIDFromBytes(("mtr_passenger_" + i).getBytes(StandardCharsets.UTF_8));
			final PlayerSkin skin = DefaultPlayerSkin.get(uuid);
			if (!skins.contains(skin)) {
				skins.add(skin);
			}
		}
		if (skins.isEmpty()) {
			skins.add(DefaultPlayerSkin.get(new UUID(0, 0)));
		}
		return skins.toArray(new PlayerSkin[0]);
	}

	/**
	 * Resolves the fetch targets for the current configuration: an explicit name list wins over
	 * the bundled premium pool (and is required for the CSL source, which is name-based).
	 */
	private static List<SkinTarget> buildTargets() {
		if (!onlineSkinsEnabled) {
			return List.of();
		}
		if (!configuredNames.isEmpty()) {
			final List<SkinTarget> targets = new ArrayList<>();
			for (final String name : configuredNames) {
				targets.add(new SkinTarget(name, null));
			}
			return targets;
		}
		if ("csl".equalsIgnoreCase(source)) {
			// The CSL API is name-based and no names are configured.
			return List.of();
		}
		return loadBundledAccounts();
	}

	/**
	 * Loads the bundled genuine-premium-account list from the mod jar.
	 */
	private static List<SkinTarget> loadBundledAccounts() {
		if (bundledAccountsLoaded) {
			return bundledAccounts;
		}
		synchronized (INIT_LOCK) {
			if (bundledAccountsLoaded) {
				return bundledAccounts;
			}
			final List<SkinTarget> targets = new ArrayList<>();
			try {
				ResourceManagerHelper.readResource(BUNDLED_ACCOUNTS_LOCATION, inputStream -> {
					try {
						final JsonObject json = JsonParser.parseString(new String(inputStream.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
						for (final JsonElement element : json.getAsJsonArray("accounts")) {
							final JsonObject account = element.getAsJsonObject();
							final String name = account.get("name").getAsString();
							final String uuid = account.get("uuid").getAsString();
							try {
								UUID.fromString(uuid);
							} catch (Exception e) {
								continue;
							}
							targets.add(new SkinTarget(name, uuid));
						}
					} catch (Exception e) {
						MTR.LOGGER.warn("Failed to read the bundled passenger skin accounts", e);
					}
				});
			} catch (Exception e) {
				MTR.LOGGER.warn("Failed to load the bundled passenger skin accounts", e);
			}
			if (!targets.isEmpty()) {
				MTR.LOGGER.info("AI passengers will wear skins from {} bundled genuine premium accounts", targets.size());
			}
			bundledAccounts = List.copyOf(targets);
			bundledAccountsLoaded = true;
			return bundledAccounts;
		}
	}

	private static void readConfig() {
		boolean online = true;
		String newSource = "mojang";
		String newCslUrl = "https://littleskin.cn";
		List<String> names = List.of();
		long refresh = DEFAULT_REFRESH_MILLIS;
		try {
			if (Files.exists(configPath)) {
				final JsonObject config = JsonParser.parseString(Files.readString(configPath, StandardCharsets.UTF_8)).getAsJsonObject();
				online = !config.has("onlineSkins") || config.get("onlineSkins").getAsBoolean();
				if (config.has("source")) {
					newSource = config.get("source").getAsString();
				}
				if (config.has("cslUrl")) {
					newCslUrl = config.get("cslUrl").getAsString();
				}
				final List<String> configured = new ArrayList<>();
				if (config.has("names")) {
					for (final JsonElement element : config.getAsJsonArray("names")) {
						final String name = element.getAsString().trim();
						if (!name.isEmpty() && !configured.contains(name) && configured.size() < MAX_ONLINE_NAMES) {
							configured.add(name);
						}
					}
				}
				names = List.copyOf(configured);
				if (config.has("refreshMinutes")) {
					refresh = Math.max(10, config.get("refreshMinutes").getAsLong()) * Utilities.MILLIS_PER_MINUTE;
				}
			} else {
				createDefaultConfig();
			}
		} catch (Exception e) {
			MTR.LOGGER.warn("Failed to read passenger skin config, using defaults", e);
		}
		onlineSkinsEnabled = online;
		source = newSource;
		cslUrl = newCslUrl;
		configuredNames = names;
		refreshMillis = refresh;
	}

	/**
	 * Auto-creates the configuration file with documented defaults on first run: online premium
	 * skins are ON, drawn from the bundled genuine-account pool.
	 */
	private static void createDefaultConfig() {
		// Auto-created on first run: online premium skins ON, drawn from the bundled pool.
		final String defaultConfig = """
				{
				  "_readme": [
				    "onlineSkins: fetch real premium-account skins for AI passengers (true/false)",
				    "source: mojang for genuine Mojang accounts, csl for a CustomSkinLoader site",
				    "cslUrl: CustomSkinLoader site base url (only used when source is csl)",
				    "names: optional explicit player-name list; while non-empty it replaces the bundled genuine-account pool (required for the csl source)",
				    "refreshMinutes: disk cache TTL in minutes (default 720 = 12 hours)"
				  ],
				  "onlineSkins": true,
				  "source": "mojang",
				  "cslUrl": "https://littleskin.cn",
				  "names": [],
				  "refreshMinutes": 720
				}
				""";
		try {
			Files.createDirectories(configPath.getParent());
			Files.writeString(configPath, defaultConfig, StandardCharsets.UTF_8);
		} catch (Exception e) {
			MTR.LOGGER.warn("Failed to create the default passenger skin config", e);
		}
	}

	private static ExecutorService fetchExecutor() {
		ExecutorService executor = fetchExecutor;
		if (executor == null) {
			synchronized (INIT_LOCK) {
				executor = fetchExecutor;
				if (executor == null) {
					executor = Executors.newFixedThreadPool(FETCH_WORKER_COUNT, runnable -> {
						final Thread thread = new Thread(runnable, "MTR Passenger Skin Fetch");
						thread.setDaemon(true);
						return thread;
					});
					fetchExecutor = executor;
				}
			}
		}
		return executor;
	}

	private static void awaitProfileRequestSlot() {
		synchronized (PROFILE_REQUEST_LOCK) {
			final long earliest = lastProfileRequestTime.get() + PROFILE_REQUEST_SPACING_MILLIS;
			final long now = System.currentTimeMillis();
			if (now < earliest) {
				try {
					Thread.sleep(earliest - now);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
			}
			lastProfileRequestTime.set(System.currentTimeMillis());
		}
	}

	private static void writeCache(String key, PlayerSkin.Model model, byte[] bytes) {
		try {
			Files.write(cacheFile(key, model), bytes);
		} catch (Exception e) {
			MTR.LOGGER.warn("Failed to cache passenger skin for {}", key, e);
		}
	}

	private static Path cacheFile(String key, PlayerSkin.Model model) {
		return cacheDir.resolve(safeFileName(key) + "." + model.id() + ".png");
	}

	private static String textureKey(String key, PlayerSkin.Model model) {
		return safeFileName(key) + "_" + model.id();
	}

	private static String httpGetJson(String url) throws Exception {
		final HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(HTTP_TIMEOUT_SECONDS)).header("User-Agent", "MTR-YanYang").header("Accept", "application/json").GET().build();
		final HttpResponse<String> response = httpClient().send(request, HttpResponse.BodyHandlers.ofString());
		if (response.statusCode() == 204 || response.statusCode() == 404) {
			return null;
		}
		if (response.statusCode() != 200) {
			throw new IllegalStateException("GET " + url + " returned " + response.statusCode());
		}
		return response.body();
	}

	private static byte[] httpGetBytes(String url) throws Exception {
		final HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(HTTP_TIMEOUT_SECONDS)).header("User-Agent", "MTR-YanYang").header("Accept", "image/png").GET().build();
		final HttpResponse<byte[]> response = httpClient().send(request, HttpResponse.BodyHandlers.ofByteArray());
		if (response.statusCode() != 200) {
			throw new IllegalStateException("Skin download returned " + response.statusCode());
		}
		return response.body();
	}

	private static HttpClient httpClient() {
		HttpClient client = sharedHttpClient;
		if (client == null) {
			synchronized (HTTP_CLIENT_LOCK) {
				client = sharedHttpClient;
				if (client == null) {
					client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(HTTP_TIMEOUT_SECONDS)).followRedirects(HttpClient.Redirect.NORMAL).build();
					sharedHttpClient = client;
				}
			}
		}
		return client;
	}

	private static String urlEncode(String value) {
		return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8);
	}

	private static String safeFileName(String name) {
		return name.replaceAll("[^a-zA-Z0-9_-]", "_").toLowerCase(Locale.ROOT);
	}

	/**
	 * One fetch target: either a bundled account (name + immutable UUID, fetched UUID-first) or
	 * a configured player name (resolved to a UUID at fetch time).
	 */
	private record SkinTarget(String name, String uuid) {

		private String key() {
			return uuid != null ? uuid : name;
		}
	}
}
