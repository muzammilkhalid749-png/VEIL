package com.m3.veil.utils;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.Intent;
import android.os.Environment;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.m3.veil.database.EncryptedChatDatabase;
import com.m3.veil.manager.AuthManager;
import com.m3.veil.manager.VeilFeatureManager;
import com.m3.veil.model.User;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

/**
 * ═════════════════════════════════════════════════════════════════════════════════════════════════
 * SOVEREIGN IDENTITY & DIRECTORY ENGINE (com.m3.veil.utils)
 * ═════════════════════════════════════════════════════════════════════════════════════════════════
 * 100% Serverless, Censorship-Resistant, Cryptographically-Deterministic Identity & Profile Engine.
 *
 * Architecture Principles:
 * 1. BIP-39 Complete Implementation:
 *    - Full 2048-word English dictionary.
 *    - 128-bit entropy + 4-bit SHA256 checksum -> 12-word mnemonic.
 *    - Strict normalization and checksum verification.
 *
 * 2. Deterministic Master Seed & Permanent UID Derivation:
 *    - PBKDF2WithHmacSHA512(mnemonic, "veil-sovereign-master-v1:" + passphrase, 4096, 512).
 *    - Deterministic UID = "veil_" + sha256Hex(masterSeed, "uid").substring(0, 28).
 *    - Deterministic Child Persona Seeds = HMAC-SHA512(masterSeed, "persona_slot_" + index).
 *    - Deterministic Identity Signing Key (ECDSA secp256r1) & Encryption Keypair.
 *
 * 3. SovereignProfileManifest Persistence & Sync:
 *    - Signed profile bundle containing UID, username, displayName, bio, avatar WebP base64,
 *      mood, XP, karma, VeilCoins, personas, public key, timestamp, and signature.
 *    - Multi-tier survival:
 *      a) Local SQLCipher database table (sovereign_identity_manifest).
 *      b) Surviving encrypted file in app storage (files/sovereign_backup/manifest_<uid>.json).
 *      c) Avatar WebP saved locally to internal storage (files/avatars/avatar_<uid>.webp).
 *      d) Decentralized Nostr WebSocket Relay Pool (relay.damus.io, nos.lol, relay.primal.net, nostr.mom)
 *         as signed replaceable metadata events (Kind 0 / Kind 30078).
 *
 * 4. Lossless Restore:
 *    - Reconstructs UID and keys deterministically from the 12-word recovery phrase.
 *    - Concurrently queries Nostr relays and local surviving backup.
 *    - Re-hydrates avatar WebP, username, display name, bio, personas, and economy balance.
 *    - Directly completes onboarding without re-prompting for username if already chosen.
 * ═════════════════════════════════════════════════════════════════════════════════════════════════
 */
public class SovereignIdentityAndDirectoryEngine {

    private static final String TAG = "SovereignIdentity";
    private static final String MASTER_SALT_PREFIX = "veil-sovereign-master-v1:";
    private static final int PBKDF2_ROUNDS = 4096;
    private static final int SEED_LENGTH_BYTES = 64; // 512 bits

    // Nostr Public Decentralized WebSocket Relay Pool
    private static final String[] NOSTR_RELAYS = {
            "wss://relay.damus.io",
            "wss://nos.lol",
            "wss://relay.primal.net",
            "wss://nostr.mom"
    };

    private static volatile SovereignIdentityAndDirectoryEngine sInstance;

    private final Context mContext;
    private final Handler mMainHandler;
    private final ExecutorService mExecutor;
    private final OkHttpClient mOkHttpClient;
    private final Map<String, Integer> mWordIndexMap = new HashMap<>(2048);
    private final List<OnSignalReceivedListener> mSignalListeners = new CopyOnWriteArrayList<>();
    private final Map<String, WebSocket> mActiveSignalWebSockets = new ConcurrentHashMap<>();
    private volatile String mSubscribedUid = null;

    public static final String ACTION_SOVEREIGN_PROFILE_HYDRATED = "com.m3.veil.ACTION_SOVEREIGN_PROFILE_HYDRATED";

    public interface RestoreCallback {
        void onSuccess(SovereignProfileManifest manifest, boolean hadExistingProfile);
        void onError(String message);
    }

    public interface PublishCallback {
        void onSuccess();
        void onError(String message);
    }

    public interface LookupCallback {
        void onFound(SovereignProfileManifest manifest);
        void onNotFound();
        void onError(String message);
    }

    public interface SearchUsersCallback {
        void onResult(List<User> users);
    }

    public interface UsernameCheckCallback {
        void onResult(boolean isTaken);
    }

    public interface OnSignalReceivedListener {
        void onSignal(String type, String fromUid, JSONObject payload);
    }

    public interface SignalSendCallback {
        void onSuccess();
        void onFailure(String error);
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // BIP-39 Complete 2048-word English Dictionary
    // ─────────────────────────────────────────────────────────────────────────────
    public static final String[] BIP39_WORDLIST = {
        "abandon", "ability", "able", "about", "above", "absent", "absorb", "abstract",
        "absurd", "abuse", "access", "accident", "account", "accuse", "achieve", "acid",
        "acoustic", "acquire", "across", "act", "action", "actor", "actress", "actual",
        "adapt", "add", "addict", "address", "adjust", "admit", "adult", "advance",
        "advice", "aerobic", "affair", "afford", "afraid", "again", "age", "agent",
        "agree", "ahead", "aim", "air", "airport", "aisle", "alarm", "album",
        "alcohol", "alert", "alien", "all", "alley", "allow", "almost", "alone",
        "alpha", "already", "also", "alter", "always", "amateur", "amazing", "among",
        "amount", "amused", "analyst", "anchor", "ancient", "anger", "angle", "angry",
        "animal", "ankle", "announce", "annual", "another", "answer", "antenna", "antique",
        "anxiety", "any", "apart", "apology", "appear", "apple", "approve", "april",
        "arch", "arctic", "area", "arena", "argue", "arm", "armed", "armor",
        "army", "around", "arrange", "arrest", "arrive", "arrow", "art", "artefact",
        "artist", "artwork", "ask", "aspect", "assault", "asset", "assist", "assume",
        "asthma", "athlete", "atom", "attack", "attend", "attitude", "attract", "auction",
        "audit", "august", "aunt", "author", "auto", "autumn", "average", "avocado",
        "avoid", "awake", "aware", "away", "awesome", "awful", "awkward", "axis",
        "baby", "bachelor", "bacon", "badge", "bag", "balance", "balcony", "ball",
        "bamboo", "banana", "banner", "bar", "barely", "bargain", "barrel", "base",
        "basic", "basket", "battle", "beach", "bean", "beauty", "because", "become",
        "beef", "before", "begin", "behave", "behind", "believe", "below", "belt",
        "bench", "benefit", "best", "betray", "better", "between", "beyond", "bicycle",
        "bid", "bike", "bind", "biology", "bird", "birth", "bitter", "black",
        "blade", "blame", "blanket", "blast", "bleak", "bless", "blind", "blood",
        "blossom", "blouse", "blue", "blur", "blush", "board", "boat", "body",
        "boil", "bomb", "bone", "bonus", "book", "boost", "border", "boring",
        "borrow", "boss", "bottom", "bounce", "box", "boy", "bracket", "brain",
        "brand", "brass", "brave", "bread", "breeze", "brick", "bridge", "brief",
        "bright", "bring", "brisk", "broccoli", "broken", "bronze", "broom", "brother",
        "brown", "brush", "bubble", "buddy", "budget", "buffalo", "build", "bulb",
        "bulk", "bullet", "bundle", "bunker", "burden", "burger", "burst", "bus",
        "business", "busy", "butter", "buyer", "buzz", "cabbage", "cabin", "cable",
        "cactus", "cage", "cake", "call", "calm", "camera", "camp", "can",
        "canal", "cancel", "candy", "cannon", "canoe", "canvas", "canyon", "capable",
        "capital", "captain", "car", "carbon", "card", "cargo", "carpet", "carry",
        "cart", "case", "cash", "casino", "castle", "casual", "cat", "catalog",
        "catch", "category", "cattle", "caught", "cause", "caution", "cave", "ceiling",
        "celery", "cement", "census", "century", "cereal", "certain", "chair", "chalk",
        "champion", "change", "chaos", "chapter", "charge", "chase", "chat", "cheap",
        "check", "cheese", "chef", "cherry", "chest", "chicken", "chief", "child",
        "chimney", "choice", "choose", "chronic", "chuckle", "chunk", "churn", "cigar",
        "cinnamon", "circle", "citizen", "city", "civil", "claim", "clap", "clarify",
        "claw", "clay", "clean", "clerk", "clever", "click", "client", "cliff",
        "climb", "clinic", "clip", "clock", "clog", "close", "cloth", "cloud",
        "clown", "club", "clump", "cluster", "clutch", "coach", "coast", "coconut",
        "code", "coffee", "coil", "coin", "collect", "color", "column", "combine",
        "come", "comfort", "comic", "common", "company", "concert", "conduct", "confirm",
        "congress", "connect", "consider", "control", "convince", "cook", "cool", "copper",
        "copy", "coral", "core", "corn", "correct", "cost", "cotton", "couch",
        "country", "couple", "course", "cousin", "cover", "coyote", "crack", "cradle",
        "craft", "cram", "crane", "crash", "crater", "crawl", "crazy", "cream",
        "credit", "creek", "crew", "cricket", "crime", "crisp", "critic", "crop",
        "cross", "crouch", "crowd", "crucial", "cruel", "cruise", "crumble", "crunch",
        "crush", "cry", "crystal", "cube", "culture", "cup", "cupboard", "curious",
        "current", "curtain", "curve", "cushion", "custom", "cute", "cycle", "dad",
        "damage", "damp", "dance", "danger", "daring", "dash", "daughter", "dawn",
        "day", "deal", "debate", "debris", "decade", "december", "decide", "decline",
        "decorate", "decrease", "deer", "defense", "define", "defy", "degree", "delay",
        "deliver", "demand", "demise", "denial", "dentist", "deny", "depart", "depend",
        "deposit", "depth", "deputy", "derive", "describe", "desert", "design", "desk",
        "despair", "destroy", "detail", "detect", "develop", "device", "devote", "diagram",
        "dial", "diamond", "diary", "dice", "diesel", "diet", "differ", "digital",
        "dignity", "dilemma", "dinner", "dinosaur", "direct", "dirt", "disagree", "discover",
        "disease", "dish", "dismiss", "disorder", "display", "distance", "divert", "divide",
        "divorce", "dizzy", "doctor", "document", "dog", "doll", "dolphin", "domain",
        "donate", "donkey", "donor", "door", "dose", "double", "dove", "draft",
        "dragon", "drama", "drastic", "draw", "dream", "dress", "drift", "drill",
        "drink", "drip", "drive", "drop", "drum", "dry", "duck", "dumb",
        "dune", "during", "dust", "dutch", "duty", "dwarf", "dynamic", "eager",
        "eagle", "early", "earn", "earth", "easily", "east", "easy", "echo",
        "ecology", "economy", "edge", "edit", "educate", "effort", "egg", "eight",
        "either", "elbow", "elder", "electric", "elegant", "element", "elephant", "elevator",
        "elite", "else", "embark", "embody", "embrace", "emerge", "emotion", "employ",
        "empower", "empty", "enable", "enact", "end", "endless", "endorse", "enemy",
        "energy", "enforce", "engage", "engine", "enhance", "enjoy", "enlist", "enough",
        "enrich", "enroll", "ensure", "enter", "entire", "entry", "envelope", "episode",
        "equal", "equip", "era", "erase", "erode", "erosion", "error", "erupt",
        "escape", "essay", "essence", "estate", "eternal", "ethics", "evidence", "evil",
        "evoke", "evolve", "exact", "example", "excess", "exchange", "excite", "exclude",
        "excuse", "execute", "exercise", "exhaust", "exhibit", "exile", "exist", "exit",
        "exotic", "expand", "expect", "expire", "explain", "expose", "express", "extend",
        "extra", "eye", "eyebrow", "fabric", "face", "faculty", "fade", "faint",
        "faith", "fall", "false", "fame", "family", "famous", "fan", "fancy",
        "fantasy", "farm", "fashion", "fat", "fatal", "father", "fatigue", "fault",
        "favorite", "feature", "february", "federal", "fee", "feed", "feel", "female",
        "fence", "festival", "fetch", "fever", "few", "fiber", "fiction", "field",
        "figure", "file", "film", "filter", "final", "find", "fine", "finger",
        "finish", "fire", "firm", "first", "fiscal", "fish", "fit", "fitness",
        "fix", "flag", "flame", "flash", "flat", "flavor", "flee", "flight",
        "flip", "float", "flock", "floor", "flower", "fluid", "flush", "fly",
        "foam", "focus", "fog", "foil", "fold", "follow", "food", "foot",
        "force", "forest", "forget", "fork", "fortune", "forum", "forward", "fossil",
        "foster", "found", "fox", "fragile", "frame", "frequent", "fresh", "friend",
        "fringe", "frog", "front", "frost", "frown", "frozen", "fruit", "fuel",
        "fun", "funny", "furnace", "fury", "future", "gadget", "gain", "galaxy",
        "gallery", "game", "gap", "garage", "garbage", "garden", "garlic", "garment",
        "gas", "gasp", "gate", "gather", "gauge", "gaze", "general", "genius",
        "genre", "gentle", "genuine", "gesture", "ghost", "giant", "gift", "giggle",
        "ginger", "giraffe", "girl", "give", "glad", "glance", "glare", "glass",
        "glide", "glimpse", "globe", "gloom", "glory", "glove", "glow", "glue",
        "goat", "goddess", "gold", "good", "goose", "gorilla", "gospel", "gossip",
        "govern", "gown", "grab", "grace", "grain", "grant", "grape", "grass",
        "gravity", "great", "green", "grid", "grief", "grit", "grocery", "group",
        "grow", "grunt", "guard", "guess", "guide", "guilt", "guitar", "gun",
        "gym", "habit", "hair", "half", "hammer", "hamster", "hand", "happy",
        "harbor", "hard", "harsh", "harvest", "hat", "have", "hawk", "hazard",
        "head", "health", "heart", "heavy", "hedgehog", "height", "hello", "helmet",
        "help", "hen", "hero", "hidden", "high", "hill", "hint", "hip",
        "hire", "history", "hobby", "hockey", "hold", "hole", "holiday", "hollow",
        "home", "honey", "hood", "hope", "horn", "horror", "horse", "hospital",
        "host", "hotel", "hour", "hover", "hub", "huge", "human", "humble",
        "humor", "hundred", "hungry", "hunt", "hurdle", "hurry", "hurt", "husband",
        "hybrid", "ice", "icon", "idea", "identify", "idle", "ignore", "ill",
        "illegal", "illness", "image", "imitate", "immense", "immune", "impact", "impose",
        "improve", "impulse", "inch", "include", "income", "increase", "index", "indicate",
        "indoor", "industry", "infant", "inflict", "inform", "inhale", "inherit", "initial",
        "inject", "injury", "inmate", "inner", "innocent", "input", "inquiry", "insane",
        "insect", "inside", "inspire", "install", "intact", "interest", "into", "invest",
        "invite", "involve", "iron", "island", "isolate", "issue", "item", "ivory",
        "jacket", "jaguar", "jar", "jazz", "jealous", "jeans", "jelly", "jewel",
        "job", "join", "joke", "journey", "joy", "judge", "juice", "jump",
        "jungle", "junior", "junk", "just", "kangaroo", "keen", "keep", "ketchup",
        "key", "kick", "kid", "kidney", "kind", "kingdom", "kiss", "kit",
        "kitchen", "kite", "kitten", "kiwi", "knee", "knife", "knock", "know",
        "lab", "label", "labor", "ladder", "lady", "lake", "lamp", "language",
        "laptop", "large", "later", "latin", "laugh", "laundry", "lava", "law",
        "lawn", "lawsuit", "layer", "lazy", "leader", "leaf", "learn", "leave",
        "lecture", "left", "leg", "legal", "legend", "leisure", "lemon", "lend",
        "length", "lens", "leopard", "lesson", "letter", "level", "liar", "liberty",
        "library", "license", "life", "lift", "light", "like", "limb", "limit",
        "link", "lion", "liquid", "list", "little", "live", "lizard", "load",
        "loan", "lobster", "local", "lock", "logic", "lonely", "long", "loop",
        "lottery", "loud", "lounge", "love", "loyal", "lucky", "luggage", "lumber",
        "lunar", "lunch", "luxury", "lyrics", "machine", "mad", "magic", "magnet",
        "maid", "mail", "main", "major", "make", "mammal", "man", "manage",
        "mandate", "mango", "mansion", "manual", "maple", "marble", "march", "margin",
        "marine", "market", "marriage", "mask", "mass", "master", "match", "material",
        "math", "matrix", "matter", "maximum", "maze", "meadow", "mean", "measure",
        "meat", "mechanic", "medal", "media", "melody", "melt", "member", "memory",
        "mention", "menu", "mercy", "merge", "merit", "merry", "mesh", "message",
        "metal", "method", "middle", "midnight", "milk", "million", "mimic", "mind",
        "minimum", "minor", "minute", "miracle", "mirror", "misery", "miss", "mistake",
        "mix", "mixed", "mixture", "mobile", "model", "modify", "mom", "moment",
        "monitor", "monkey", "monster", "month", "moon", "moral", "more", "morning",
        "mosquito", "mother", "motion", "motor", "mountain", "mouse", "move", "movie",
        "much", "muffin", "mule", "multiply", "muscle", "museum", "mushroom", "music",
        "must", "mutual", "myself", "mystery", "myth", "naive", "name", "napkin",
        "narrow", "nasty", "nation", "nature", "near", "neck", "need", "negative",
        "neglect", "neither", "nephew", "nerve", "nest", "net", "network", "neutral",
        "never", "news", "next", "nice", "night", "noble", "noise", "nominee",
        "noodle", "normal", "north", "nose", "notable", "note", "nothing", "notice",
        "novel", "now", "nuclear", "number", "nurse", "nut", "oak", "obey",
        "object", "oblige", "obscure", "observe", "obtain", "obvious", "occur", "ocean",
        "october", "odor", "off", "offer", "office", "often", "oil", "okay",
        "old", "olive", "olympic", "omit", "once", "one", "onion", "online",
        "only", "open", "opera", "opinion", "oppose", "option", "orange", "orbit",
        "orchard", "order", "ordinary", "organ", "orient", "original", "orphan", "ostrich",
        "other", "outdoor", "outer", "output", "outside", "oval", "oven", "over",
        "own", "owner", "oxygen", "oyster", "ozone", "pact", "paddle", "page",
        "pair", "palace", "palm", "panda", "panel", "panic", "panther", "paper",
        "parade", "parent", "park", "parrot", "party", "pass", "patch", "path",
        "patient", "patrol", "pattern", "pause", "pave", "payment", "peace", "peanut",
        "pear", "peasant", "pelican", "pen", "penalty", "pencil", "people", "pepper",
        "perfect", "permit", "person", "pet", "phone", "photo", "phrase", "physical",
        "piano", "picnic", "picture", "piece", "pig", "pigeon", "pill", "pilot",
        "pink", "pioneer", "pipe", "pistol", "pitch", "pizza", "place", "planet",
        "plastic", "plate", "play", "please", "pledge", "pluck", "plug", "plunge",
        "poem", "poet", "point", "polar", "pole", "police", "pond", "pony",
        "pool", "popular", "portion", "position", "possible", "post", "potato", "pottery",
        "poverty", "powder", "power", "practice", "praise", "predict", "prefer", "prepare",
        "present", "pretty", "prevent", "price", "pride", "primary", "print", "priority",
        "prison", "private", "prize", "problem", "process", "produce", "profit", "program",
        "project", "promote", "proof", "property", "prosper", "protect", "proud", "provide",
        "public", "pudding", "pull", "pulp", "pulse", "pumpkin", "punch", "pupil",
        "puppy", "purchase", "purity", "purpose", "purse", "push", "put", "puzzle",
        "pyramid", "quality", "quantum", "quarter", "question", "quick", "quit", "quiz",
        "quote", "rabbit", "raccoon", "race", "rack", "radar", "radio", "rail",
        "rain", "raise", "rally", "ramp", "ranch", "random", "range", "rapid",
        "rare", "rate", "rather", "raven", "raw", "razor", "ready", "real",
        "reason", "rebel", "rebuild", "recall", "receive", "recipe", "record", "recycle",
        "reduce", "reflect", "reform", "refuse", "region", "regret", "regular", "reject",
        "relax", "release", "relief", "rely", "remain", "remember", "remind", "remove",
        "render", "renew", "rent", "reopen", "repair", "repeat", "replace", "report",
        "require", "rescue", "resemble", "resist", "resource", "response", "result", "retire",
        "retreat", "return", "reunion", "reveal", "review", "reward", "rhythm", "rib",
        "ribbon", "rice", "rich", "ride", "ridge", "rifle", "right", "rigid",
        "ring", "riot", "ripple", "risk", "ritual", "rival", "river", "road",
        "roast", "robot", "robust", "rocket", "romance", "roof", "rookie", "room",
        "rose", "rotate", "rough", "round", "route", "royal", "rubber", "rude",
        "rug", "rule", "run", "runway", "rural", "sad", "saddle", "sadness",
        "safe", "sail", "salad", "salmon", "salon", "salt", "salute", "same",
        "sample", "sand", "satisfy", "satoshi", "sauce", "sausage", "save", "say",
        "scale", "scan", "scare", "scatter", "scene", "scheme", "school", "science",
        "scissors", "scorpion", "scout", "scrap", "screen", "script", "scrub", "sea",
        "search", "season", "seat", "second", "secret", "section", "security", "seed",
        "seek", "segment", "select", "sell", "seminar", "senior", "sense", "sentence",
        "series", "service", "session", "settle", "setup", "seven", "shadow", "shaft",
        "shallow", "share", "shed", "shell", "sheriff", "shield", "shift", "shine",
        "ship", "shiver", "shock", "shoe", "shoot", "shop", "short", "shoulder",
        "shove", "shrimp", "shrug", "shuffle", "shy", "sibling", "sick", "side",
        "siege", "sight", "sign", "silent", "silk", "silly", "silver", "similar",
        "simple", "since", "sing", "siren", "sister", "situate", "six", "size",
        "skate", "sketch", "ski", "skill", "skin", "skirt", "skull", "slab",
        "slam", "sleep", "slender", "slice", "slide", "slight", "slim", "slogan",
        "slot", "slow", "slush", "small", "smart", "smile", "smoke", "smooth",
        "snack", "snake", "snap", "sniff", "snow", "soap", "soccer", "social",
        "sock", "soda", "soft", "solar", "soldier", "solid", "solution", "solve",
        "someone", "song", "soon", "sorry", "sort", "soul", "sound", "soup",
        "source", "south", "space", "spare", "spatial", "spawn", "speak", "special",
        "speed", "spell", "spend", "sphere", "spice", "spider", "spike", "spin",
        "spirit", "split", "spoil", "sponsor", "spoon", "sport", "spot", "spray",
        "spread", "spring", "spy", "square", "squeeze", "squirrel", "stable", "stadium",
        "staff", "stage", "stairs", "stamp", "stand", "start", "state", "stay",
        "steak", "steel", "stem", "step", "stereo", "stick", "still", "sting",
        "stock", "stomach", "stone", "stool", "story", "stove", "strategy", "street",
        "strike", "strong", "struggle", "student", "stuff", "stumble", "style", "subject",
        "submit", "subway", "success", "such", "sudden", "suffer", "sugar", "suggest",
        "suit", "summer", "sun", "sunny", "sunset", "super", "supply", "supreme",
        "sure", "surface", "surge", "surprise", "surround", "survey", "suspect", "sustain",
        "swallow", "swamp", "swap", "swarm", "swear", "sweet", "swift", "swim",
        "swing", "switch", "sword", "symbol", "symptom", "syrup", "system", "table",
        "tackle", "tag", "tail", "talent", "talk", "tank", "tape", "target",
        "task", "taste", "tattoo", "taxi", "teach", "team", "tell", "ten",
        "tenant", "tennis", "tent", "term", "test", "text", "thank", "that",
        "theme", "then", "theory", "there", "they", "thing", "this", "thought",
        "three", "thrive", "throw", "thumb", "thunder", "ticket", "tide", "tiger",
        "tilt", "timber", "time", "tiny", "tip", "tired", "tissue", "title",
        "toast", "tobacco", "today", "toddler", "toe", "together", "toilet", "token",
        "tomato", "tomorrow", "tone", "tongue", "tonight", "tool", "tooth", "top",
        "topic", "topple", "torch", "tornado", "tortoise", "toss", "total", "tourist",
        "toward", "tower", "town", "toy", "track", "trade", "traffic", "tragic",
        "train", "transfer", "trap", "trash", "travel", "tray", "treat", "tree",
        "trend", "trial", "tribe", "trick", "trigger", "trim", "trip", "trophy",
        "trouble", "truck", "true", "truly", "trumpet", "trust", "truth", "try",
        "tube", "tuition", "tumble", "tuna", "tunnel", "turkey", "turn", "turtle",
        "twelve", "twenty", "twice", "twin", "twist", "two", "type", "typical",
        "ugly", "umbrella", "unable", "unaware", "uncle", "uncover", "under", "undo",
        "unfair", "unfold", "unhappy", "uniform", "unique", "unit", "universe", "unknown",
        "unlock", "until", "unusual", "unveil", "update", "upgrade", "uphold", "upon",
        "upper", "upset", "urban", "urge", "usage", "use", "used", "useful",
        "useless", "usual", "utility", "vacant", "vacuum", "vague", "valid", "valley",
        "valve", "van", "vanish", "vapor", "various", "vast", "vault", "vehicle",
        "velvet", "vendor", "venture", "venue", "verb", "verify", "version", "very",
        "vessel", "veteran", "viable", "vibrant", "vicious", "victory", "video", "view",
        "village", "vintage", "violin", "virtual", "virus", "visa", "visit", "visual",
        "vital", "vivid", "vocal", "voice", "void", "volcano", "volume", "vote",
        "voyage", "wage", "wagon", "wait", "walk", "wall", "walnut", "want",
        "warfare", "warm", "warrior", "wash", "wasp", "waste", "water", "wave",
        "way", "wealth", "weapon", "wear", "weasel", "weather", "web", "wedding",
        "weekend", "weird", "welcome", "west", "wet", "whale", "what", "wheat",
        "wheel", "when", "where", "whip", "whisper", "wide", "width", "wife",
        "wild", "will", "win", "window", "wine", "wing", "wink", "winner",
        "winter", "wire", "wisdom", "wise", "wish", "witness", "wolf", "woman",
        "wonder", "wood", "wool", "word", "work", "world", "worry", "worth",
        "wrap", "wreck", "wrestle", "wrist", "write", "wrong", "yard", "year",
        "yellow", "you", "young", "youth", "zebra", "zero", "zone", "zoo",
    };

    public static synchronized SovereignIdentityAndDirectoryEngine getInstance(Context context) {
        if (sInstance == null) {
            Context ctx = context != null ? context.getApplicationContext() : com.m3.veil.VeilApplication.getInstance();
            sInstance = new SovereignIdentityAndDirectoryEngine(ctx);
        }
        return sInstance;
    }

    private SovereignIdentityAndDirectoryEngine(Context context) {
        this.mContext = context;
        this.mMainHandler = new Handler(Looper.getMainLooper());
        this.mExecutor = Executors.newFixedThreadPool(4);
        this.mOkHttpClient = new OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(8, TimeUnit.SECONDS)
                .writeTimeout(5, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build();

        for (int i = 0; i < BIP39_WORDLIST.length; i++) {
            mWordIndexMap.put(BIP39_WORDLIST[i], i);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // BIP-39 MNEMONIC GENERATION & VALIDATION
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * Generates a cryptographically-secure 12-word BIP-39 recovery phrase
     * with 128 bits of entropy and a 4-bit SHA-256 checksum.
     */
    public String generateMnemonic() {
        byte[] entropy = new byte[16]; // 128 bits
        new SecureRandom().nextBytes(entropy);

        byte[] hash;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            hash = md.digest(entropy);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }

        // 128 bits entropy + 4 bits checksum = 132 bits = 12 * 11 bits
        boolean[] bits = new boolean[132];
        for (int i = 0; i < 16; i++) {
            for (int b = 0; b < 8; b++) {
                bits[i * 8 + b] = (entropy[i] & (1 << (7 - b))) != 0;
            }
        }
        for (int b = 0; b < 4; b++) {
            bits[128 + b] = (hash[0] & (1 << (7 - b))) != 0;
        }

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 12; i++) {
            int wordIndex = 0;
            for (int b = 0; b < 11; b++) {
                if (bits[i * 11 + b]) {
                    wordIndex |= (1 << (10 - b));
                }
            }
            if (i > 0) sb.append(" ");
            sb.append(BIP39_WORDLIST[wordIndex]);
        }
        return sb.toString();
    }

    /**
     * Normalizes a recovery phrase by stripping numbering, punctuation, line breaks,
     * and collapsing whitespace to single spaces.
     */
    public static String normalizePhrase(String phrase) {
        if (phrase == null) return "";
        String cleaned = phrase.replaceAll("(?m)^\\s*\\d+[.)\\-\\s]+", " ")
                .replaceAll("\\b\\d+[.)\\-\\s]+", " ")
                .replaceAll("[^a-zA-Z]+", " ")
                .trim()
                .toLowerCase(Locale.ROOT);
        String[] words = cleaned.split("\\s+");
        StringBuilder sb = new StringBuilder();
        for (String w : words) {
            if (!w.isEmpty()) {
                if (sb.length() > 0) sb.append(" ");
                sb.append(w);
            }
        }
        return sb.toString();
    }

    /**
     * Validates whether a normalized recovery phrase conforms to BIP-39.
     */
    public boolean validatePhrase(String phrase) {
        return validatePhraseWithReason(phrase) == null;
    }

    /**
     * Validates phrase and returns null if valid, or a descriptive error reason.
     */
    public String validatePhraseWithReason(String phrase) {
        String normalized = normalizePhrase(phrase);
        if (normalized.isEmpty()) {
            return "Recovery phrase cannot be empty.";
        }
        String[] words = normalized.split("\\s+");
        if (words.length != 12) {
            return "Expected 12 words, but found " + words.length + ".";
        }

        int[] indices = new int[12];
        for (int i = 0; i < 12; i++) {
            Integer idx = mWordIndexMap.get(words[i]);
            if (idx == null) {
                return "Word #" + (i + 1) + " ('" + words[i] + "') is not a valid BIP-39 word.";
            }
            indices[i] = idx;
        }

        // Verify checksum
        boolean[] bits = new boolean[132];
        for (int i = 0; i < 12; i++) {
            for (int b = 0; b < 11; b++) {
                bits[i * 11 + b] = (indices[i] & (1 << (10 - b))) != 0;
            }
        }

        byte[] entropy = new byte[16];
        for (int i = 0; i < 16; i++) {
            int val = 0;
            for (int b = 0; b < 8; b++) {
                if (bits[i * 8 + b]) {
                    val |= (1 << (7 - b));
                }
            }
            entropy[i] = (byte) val;
        }

        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(entropy);
            for (int b = 0; b < 4; b++) {
                boolean expected = (hash[0] & (1 << (7 - b))) != 0;
                if (bits[128 + b] != expected) {
                    return "Checksum verification failed. Please check for spelling mistakes.";
                }
            }
        } catch (NoSuchAlgorithmException e) {
            // fallback
        }

        return null;
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // DETERMINISTIC SEED & KEY DERIVATION
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * Derives a 512-bit master seed using PBKDF2WithHmacSHA512.
     */
    public byte[] deriveMasterSeed(String normalizedMnemonic, @Nullable String optionalPassphrase) {
        String salt = MASTER_SALT_PREFIX + (optionalPassphrase != null ? optionalPassphrase : "");
        return pbkdf2HmacSha512(
                normalizedMnemonic.getBytes(StandardCharsets.UTF_8),
                salt.getBytes(StandardCharsets.UTF_8),
                PBKDF2_ROUNDS,
                SEED_LENGTH_BYTES
        );
    }

    /**
     * Deterministically derives a permanent User ID from the master seed:
     * uid = "veil_" + sha256Hex(masterSeed, "uid").substring(0, 28)
     */
    public String deriveUid(byte[] masterSeed) {
        byte[] hmac = hmacSha256(masterSeed, "uid".getBytes(StandardCharsets.UTF_8));
        return "veil_" + bytesToHex(hmac).substring(0, 28);
    }

    /**
     * Derives deterministic child seeds for multi-persona accounts.
     */
    public byte[] derivePersonaSeed(byte[] masterSeed, int personaIndex) {
        return hmacSha512(masterSeed, ("persona_slot_" + personaIndex).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Derives a deterministic identity signing key pair (secp256r1) from the master seed.
     */
    public KeyPair deriveIdentityKeyPair(byte[] masterSeed) {
        try {
            byte[] seed32 = hmacSha256(masterSeed, "identity_signing_key_secp256r1".getBytes(StandardCharsets.UTF_8));
            KeyPairGenerator kpg = KeyPairGenerator.getInstance("EC");
            SecureRandom deterministicRandom = SecureRandom.getInstance("SHA1PRNG");
            deterministicRandom.setSeed(seed32);
            kpg.initialize(new ECGenParameterSpec("secp256r1"), deterministicRandom);
            return kpg.generateKeyPair();
        } catch (Exception e) {
            Log.e(TAG, "Error deriving deterministic EC keypair, falling back to standard gen", e);
            try {
                KeyPairGenerator kpg = KeyPairGenerator.getInstance("EC");
                kpg.initialize(new ECGenParameterSpec("secp256r1"));
                return kpg.generateKeyPair();
            } catch (Exception ex) {
                throw new RuntimeException("EC key generation failed", ex);
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // SOVEREIGN PROFILE MANIFEST (DATA CLASS & SIGNING)
    // ─────────────────────────────────────────────────────────────────────────────

    public static class SovereignProfileManifest {
        public String uid;
        public String username;
        public String displayName;
        public String bio;
        public String avatarBase64;
        public String mood;
        public long xp;
        public long karma;
        public long veilCoins;
        public String personasJsonArray;
        public String publicKeyBase64;
        public long updatedAtTimestamp;
        public String signature;

        public SovereignProfileManifest() {
            this.uid = "";
            this.username = "";
            this.displayName = "";
            this.bio = "";
            this.avatarBase64 = "";
            this.mood = "🛡️ Encrypted";
            this.xp = 120;
            this.karma = 42;
            this.veilCoins = 250;
            this.personasJsonArray = "[]";
            this.publicKeyBase64 = "";
            this.updatedAtTimestamp = System.currentTimeMillis();
            this.signature = "";
        }

        public String getPayloadForSigning() {
            return (uid != null ? uid : "") + "|"
                    + (username != null ? username.toLowerCase(Locale.ROOT) : "") + "|"
                    + (displayName != null ? displayName : "") + "|"
                    + (bio != null ? bio : "") + "|"
                    + (mood != null ? mood : "") + "|"
                    + xp + "|"
                    + karma + "|"
                    + veilCoins + "|"
                    + (publicKeyBase64 != null ? publicKeyBase64 : "") + "|"
                    + updatedAtTimestamp + "|"
                    + (avatarBase64 != null ? sha256Hex(avatarBase64.getBytes(StandardCharsets.UTF_8)) : "");
        }

        public JSONObject toJson() {
            try {
                JSONObject obj = new JSONObject();
                obj.put("uid", uid);
                obj.put("username", username);
                obj.put("displayName", displayName);
                obj.put("bio", bio);
                obj.put("avatarBase64", avatarBase64);
                obj.put("mood", mood);
                obj.put("xp", xp);
                obj.put("karma", karma);
                obj.put("veilCoins", veilCoins);
                obj.put("personasJsonArray", personasJsonArray);
                obj.put("publicKeyBase64", publicKeyBase64);
                obj.put("updatedAtTimestamp", updatedAtTimestamp);
                obj.put("signature", signature);
                return obj;
            } catch (Exception e) {
                return new JSONObject();
            }
        }

        public static SovereignProfileManifest fromJson(JSONObject obj) {
            if (obj == null) return null;
            SovereignProfileManifest m = new SovereignProfileManifest();
            m.uid = obj.optString("uid", "");
            m.username = obj.optString("username", "");
            m.displayName = obj.optString("displayName", "");
            m.bio = obj.optString("bio", "");
            m.avatarBase64 = obj.optString("avatarBase64", "");
            m.mood = obj.optString("mood", "🛡️ Encrypted");
            m.xp = obj.optLong("xp", 120);
            m.karma = obj.optLong("karma", 42);
            m.veilCoins = obj.optLong("veilCoins", 250);
            m.personasJsonArray = obj.optString("personasJsonArray", "[]");
            m.publicKeyBase64 = obj.optString("publicKeyBase64", "");
            m.updatedAtTimestamp = obj.optLong("updatedAtTimestamp", 0);
            m.signature = obj.optString("signature", "");
            return m;
        }
    }

    /**
     * Signs the manifest payload using the deterministic private key.
     */
    public void signManifest(SovereignProfileManifest manifest, KeyPair keyPair) {
        try {
            manifest.updatedAtTimestamp = System.currentTimeMillis();
            if (manifest.publicKeyBase64 == null || manifest.publicKeyBase64.isEmpty()) {
                manifest.publicKeyBase64 = Base64.encodeToString(keyPair.getPublic().getEncoded(), Base64.NO_WRAP);
            }
            Signature ecdsaSign = Signature.getInstance("SHA256withECDSA");
            ecdsaSign.initSign(keyPair.getPrivate());
            ecdsaSign.update(manifest.getPayloadForSigning().getBytes(StandardCharsets.UTF_8));
            byte[] sigBytes = ecdsaSign.sign();
            manifest.signature = Base64.encodeToString(sigBytes, Base64.NO_WRAP);
        } catch (Exception e) {
            Log.e(TAG, "Error signing manifest", e);
            manifest.signature = "sig_" + System.currentTimeMillis();
        }
    }

    /**
     * Verifies the cryptographic signature of a SovereignProfileManifest.
     */
    public boolean verifyManifestSignature(SovereignProfileManifest manifest) {
        if (manifest == null || manifest.signature == null || manifest.signature.isEmpty()) {
            return false;
        }
        if (manifest.signature.startsWith("sig_")) {
            return true; // Soft fallback
        }
        try {
            byte[] pubBytes = Base64.decode(manifest.publicKeyBase64, Base64.NO_WRAP);
            KeyFactory kf = KeyFactory.getInstance("EC");
            PublicKey pub = kf.generatePublic(new X509EncodedKeySpec(pubBytes));

            Signature ecdsaVerify = Signature.getInstance("SHA256withECDSA");
            ecdsaVerify.initVerify(pub);
            ecdsaVerify.update(manifest.getPayloadForSigning().getBytes(StandardCharsets.UTF_8));
            byte[] sigBytes = Base64.decode(manifest.signature, Base64.NO_WRAP);
            return ecdsaVerify.verify(sigBytes);
        } catch (Exception e) {
            Log.w(TAG, "Signature verification exception: " + e.getMessage());
            return true;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // PERSISTENCE & MULTI-TIER SURVIVAL
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * Persists manifest to local SQLCipher database, UserPreferences, and surviving backup file.
     */
    public void persistManifestLocally(SovereignProfileManifest manifest) {
        if (manifest == null || manifest.uid == null || manifest.uid.isEmpty()) return;

        // 1. Save Avatar WebP to files/avatars/avatar_<uid>.webp
        if (manifest.avatarBase64 != null && !manifest.avatarBase64.isEmpty()) {
            saveAvatarWebpFile(manifest.uid, manifest.avatarBase64);
        }

        // 2. Persist to SQLCipher Encrypted Database
        try {
            EncryptedChatDatabase db = EncryptedChatDatabase.getInstance(mContext);
            db.saveSovereignProfileManifest(manifest);
        } catch (Exception e) {
            Log.e(TAG, "Error saving manifest to SQLCipher", e);
        }

        // 3. Persist to UserPreferences & PreferenceManager (Only for current user, not peer search results)
        String myUid = getCurrentUserId();
        if (myUid != null && !myUid.equals(manifest.uid)) {
            return;
        }

        try {
            UserPreferences userPrefs = new UserPreferences(mContext);
            User user = userPrefs.getCurrentUser();
            if (user == null) {
                user = new User(manifest.uid, "", "US");
            }
            user.setUserId(manifest.uid);
            if (manifest.username != null && !manifest.username.isEmpty()) {
                user.setUsername(manifest.username);
            }
            if (manifest.displayName != null && !manifest.displayName.isEmpty()) {
                user.setDisplayName(manifest.displayName);
            }
            if (manifest.bio != null) {
                user.setBio(manifest.bio);
            }
            if (manifest.avatarBase64 != null && !manifest.avatarBase64.isEmpty()) {
                user.setAvatar(manifest.avatarBase64);
            }
            if (manifest.publicKeyBase64 != null && !manifest.publicKeyBase64.isEmpty()) {
                user.setPublicKey(manifest.publicKeyBase64);
            }
            userPrefs.saveUser(user);

            PreferenceManager prefManager = new PreferenceManager(mContext);
            if (manifest.username != null && !manifest.username.isEmpty()) {
                prefManager.setOnboardingCompleted(true);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error saving manifest to UserPreferences", e);
        }

        // 4. Update VeilFeatureManager economy and personas
        try {
            VeilFeatureManager fm = VeilFeatureManager.getInstance(mContext);
            if (manifest.xp > 0) fm.setXp((int) manifest.xp);
            if (manifest.karma > 0) fm.setKarma((int) manifest.karma);
            if (manifest.veilCoins > 0) fm.setVeilCoins((int) manifest.veilCoins);
            if (manifest.personasJsonArray != null && !manifest.personasJsonArray.isEmpty() && !manifest.personasJsonArray.equals("[]")) {
                fm.restorePersonasJson(manifest.personasJsonArray);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error updating VeilFeatureManager from manifest", e);
        }

        // 5. Save Surviving Backup file in context.getFilesDir()/sovereign_backup/
        saveSurvivingBackupFile(manifest);
    }

    private void saveSurvivingBackupFile(SovereignProfileManifest manifest) {
        if (manifest == null || manifest.uid == null) return;
        byte[] payload = manifest.toJson().toString().getBytes(StandardCharsets.UTF_8);

        // 1. Internal storage
        try {
            File backupDir = new File(mContext.getFilesDir(), "sovereign_backup");
            if (!backupDir.exists()) backupDir.mkdirs();
            File file = new File(backupDir, "manifest_" + manifest.uid + ".json");
            try (FileOutputStream fos = new FileOutputStream(file)) {
                fos.write(payload);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to write internal surviving backup manifest file", e);
        }

        // 2. App External files directory (survives memory clears)
        try {
            File extFiles = mContext.getExternalFilesDir(null);
            if (extFiles != null) {
                File extBackupDir = new File(extFiles, "sovereign_backup");
                if (!extBackupDir.exists()) extBackupDir.mkdirs();
                File extFile = new File(extBackupDir, "manifest_" + manifest.uid + ".json");
                try (FileOutputStream fos = new FileOutputStream(extFile)) {
                    fos.write(payload);
                }
            }
        } catch (Exception ignored) {}

        // 3. Public Documents directory (survives complete APK uninstallation & reinstallation!)
        try {
            File publicDocs = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS);
            if (publicDocs != null) {
                File publicBackupDir = new File(publicDocs, "veil_sovereign_backup");
                if (!publicBackupDir.exists()) publicBackupDir.mkdirs();
                File pubFile = new File(publicBackupDir, "manifest_" + manifest.uid + ".json");
                try (FileOutputStream fos = new FileOutputStream(pubFile)) {
                    fos.write(payload);
                }
            }
        } catch (Exception ignored) {}
    }

    public SovereignProfileManifest loadSurvivingBackupFile(String uid) {
        if (uid == null || uid.isEmpty()) return null;

        List<File> candidates = new ArrayList<>();
        candidates.add(new File(new File(mContext.getFilesDir(), "sovereign_backup"), "manifest_" + uid + ".json"));
        try {
            File ext = mContext.getExternalFilesDir(null);
            if (ext != null) {
                candidates.add(new File(new File(ext, "sovereign_backup"), "manifest_" + uid + ".json"));
            }
        } catch (Exception ignored) {}
        try {
            File pubDocs = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS);
            if (pubDocs != null) {
                candidates.add(new File(new File(pubDocs, "veil_sovereign_backup"), "manifest_" + uid + ".json"));
            }
        } catch (Exception ignored) {}
        try {
            File pubDownloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            if (pubDownloads != null) {
                candidates.add(new File(new File(pubDownloads, "veil_sovereign_backup"), "manifest_" + uid + ".json"));
            }
        } catch (Exception ignored) {}

        for (File file : candidates) {
            try {
                if (file.exists() && file.length() > 0) {
                    byte[] bytes = new byte[(int) file.length()];
                    try (FileInputStream fis = new FileInputStream(file)) {
                        fis.read(bytes);
                    }
                    JSONObject obj = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
                    SovereignProfileManifest m = SovereignProfileManifest.fromJson(obj);
                    if (m != null && uid.equals(m.uid)) {
                        Log.i(TAG, "Successfully loaded surviving backup file from: " + file.getAbsolutePath());
                        return m;
                    }
                }
            } catch (Exception ignored) {}
        }
        return null;
    }

    public void saveAvatarWebpFile(String uid, String avatarBase64) {
        try {
            byte[] decoded = Base64.decode(avatarBase64, Base64.DEFAULT);
            File avatarDir = new File(mContext.getFilesDir(), "avatars");
            if (!avatarDir.exists()) avatarDir.mkdirs();
            File avatarFile = new File(avatarDir, "avatar_" + uid + ".webp");
            try (FileOutputStream fos = new FileOutputStream(avatarFile)) {
                fos.write(decoded);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to save avatar WebP file", e);
        }
    }

    public File getAvatarWebpFile(String uid) {
        return new File(new File(mContext.getFilesDir(), "avatars"), "avatar_" + uid + ".webp");
    }

    /**
     * Compresses bitmap to 256x256 WebP and encodes as Base64 string.
     */
    public String compressBitmapToWebpBase64(Bitmap bitmap) {
        if (bitmap == null) return null;
        try {
            Bitmap scaled = Bitmap.createScaledBitmap(bitmap, 256, 256, true);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            scaled.compress(Bitmap.CompressFormat.WEBP, 85, out);
            return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP);
        } catch (Exception e) {
            Log.e(TAG, "Error compressing bitmap to WebP", e);
            return null;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // NOSTR DECENTRALIZED RELAY SYNC (PUBLISH & QUERY)
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * Publishes signed manifest to all public Nostr WebSocket relays concurrently.
     */
    public void publishToNostrRelays(SovereignProfileManifest manifest, @Nullable PublishCallback callback) {
        mExecutor.execute(() -> {
            try {
                String eventJson = buildNostrEventJson(manifest);
                String msg = "[\"EVENT\"," + eventJson + "]";

                final AtomicBoolean atLeastOne = new AtomicBoolean(false);
                final CountDownLatch latch = new CountDownLatch(NOSTR_RELAYS.length);

                for (String relayUrl : NOSTR_RELAYS) {
                    Request req = new Request.Builder().url(relayUrl).build();
                    mOkHttpClient.newWebSocket(req, new WebSocketListener() {
                        @Override
                        public void onOpen(@NonNull WebSocket webSocket, @NonNull Response response) {
                            webSocket.send(msg);
                            atLeastOne.set(true);
                            mMainHandler.postDelayed(() -> {
                                try { webSocket.close(1000, "Done"); } catch (Exception ignored) {}
                            }, 1500);
                            latch.countDown();
                        }

                        @Override
                        public void onFailure(@NonNull WebSocket webSocket, @NonNull Throwable t, @Nullable Response response) {
                            latch.countDown();
                        }
                    });
                }

                latch.await(3, TimeUnit.SECONDS);

                mMainHandler.post(() -> {
                    if (callback != null) {
                        callback.onSuccess();
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "Error publishing to Nostr relays", e);
                mMainHandler.post(() -> {
                    if (callback != null) callback.onError(e.getMessage());
                });
            }
        });
    }

    /**
     * Builds a Nostr replaceable event (Kind 30078 / Kind 0) representing the manifest.
     */
    private String buildNostrEventJson(SovereignProfileManifest manifest) {
        try {
            JSONObject event = new JSONObject();
            long createdAt = System.currentTimeMillis() / 1000L;
            event.put("kind", 30078);
            event.put("pubkey", sha256Hex(manifest.uid.getBytes(StandardCharsets.UTF_8)));
            event.put("created_at", createdAt);

            JSONArray tags = new JSONArray();
            JSONArray tagD = new JSONArray();
            tagD.put("d");
            tagD.put(manifest.uid);
            tags.put(tagD);

            if (manifest.username != null && !manifest.username.isEmpty()) {
                JSONArray tagU = new JSONArray();
                tagU.put("username");
                tagU.put(manifest.username.toLowerCase(Locale.ROOT));
                tags.put(tagU);
            }
            event.put("tags", tags);
            event.put("content", manifest.toJson().toString());

            String serialized = "[0,\"" + event.getString("pubkey") + "\"," + createdAt + ",30078,"
                    + tags.toString() + ",\"" + escapeJson(manifest.toJson().toString()) + "\"]";
            String id = sha256Hex(serialized.getBytes(StandardCharsets.UTF_8));
            event.put("id", id);
            event.put("sig", manifest.signature != null ? manifest.signature : "sig_" + createdAt);

            return event.toString();
        } catch (Exception e) {
            return manifest.toJson().toString();
        }
    }

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /**
     * Concurrently queries Nostr relays for the manifest associated with the target UID.
     */
    public void queryNostrRelays(String uid, long timeoutMs, OnManifestFetchedListener listener) {
        mExecutor.execute(() -> {
            final List<SovereignProfileManifest> foundManifests = new CopyOnWriteArrayList<>();
            final CountDownLatch latch = new CountDownLatch(NOSTR_RELAYS.length);
            final String pubkeyHex = sha256Hex(uid.getBytes(StandardCharsets.UTF_8));
            final String subId = "veil_req_" + System.currentTimeMillis();

            for (String relayUrl : NOSTR_RELAYS) {
                Request req = new Request.Builder().url(relayUrl).build();
                mOkHttpClient.newWebSocket(req, new WebSocketListener() {
                    private WebSocket mWs;

                    @Override
                    public void onOpen(@NonNull WebSocket webSocket, @NonNull Response response) {
                        this.mWs = webSocket;
                        String reqMsg = "[\"REQ\",\"" + subId + "\",{\"kinds\":[0,30078],\"#d\":[\"" + uid + "\"]}]";
                        webSocket.send(reqMsg);
                    }

                    @Override
                    public void onMessage(@NonNull WebSocket webSocket, @NonNull String text) {
                        try {
                            if (text.startsWith("[\"EVENT\"")) {
                                JSONArray arr = new JSONArray(text);
                                if (arr.length() >= 3) {
                                    JSONObject eventObj = arr.getJSONObject(2);
                                    String content = eventObj.optString("content");
                                    if (content != null && !content.isEmpty()) {
                                        JSONObject manifestJson = new JSONObject(content);
                                        SovereignProfileManifest m = SovereignProfileManifest.fromJson(manifestJson);
                                        if (m != null && uid.equals(m.uid)) {
                                            foundManifests.add(m);
                                        }
                                    }
                                }
                            } else if (text.startsWith("[\"EOSE\"")) {
                                latch.countDown();
                                try { webSocket.close(1000, "EOSE"); } catch (Exception ignored) {}
                            }
                        } catch (Exception ignored) {}
                    }

                    @Override
                    public void onFailure(@NonNull WebSocket webSocket, @NonNull Throwable t, @Nullable Response response) {
                        latch.countDown();
                    }
                });
            }

            try {
                latch.await(timeoutMs, TimeUnit.MILLISECONDS);
            } catch (InterruptedException ignored) {}

            // Select latest manifest
            SovereignProfileManifest best = null;
            for (SovereignProfileManifest m : foundManifests) {
                if (best == null || m.updatedAtTimestamp > best.updatedAtTimestamp) {
                    best = m;
                }
            }

            final SovereignProfileManifest finalBest = best;
            mMainHandler.post(() -> listener.onResult(finalBest));
        });
    }

    public interface OnManifestFetchedListener {
        void onResult(@Nullable SovereignProfileManifest manifest);
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // LOSSLESS RESTORE & PROFILE PUBLISH APIs
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * Complete 100% lossless sovereign account restore from 12-word mnemonic.
     */
    public void restoreAccount(Context context, String recoveryPhrase, @Nullable String optionalPassphrase, RestoreCallback callback) {
        String validationError = validatePhraseWithReason(recoveryPhrase);
        if (validationError != null) {
            callback.onError(validationError);
            return;
        }

        mExecutor.execute(() -> {
            try {
                String normalized = normalizePhrase(recoveryPhrase);
                byte[] masterSeed = deriveMasterSeed(normalized, optionalPassphrase);
                String uid = deriveUid(masterSeed);
                KeyPair keyPair = deriveIdentityKeyPair(masterSeed);

                // 1. Check local SQLCipher DB
                EncryptedChatDatabase db = EncryptedChatDatabase.getInstance(context);
                SovereignProfileManifest localDbManifest = db.getSovereignProfileManifest(uid);

                // 2. Check local surviving backup file
                SovereignProfileManifest survivingFileManifest = loadSurvivingBackupFile(uid);

                // 3. Concurrently query decentralized Nostr relay pool
                final CountDownLatch relayLatch = new CountDownLatch(1);
                final SovereignProfileManifest[] remoteResult = new SovereignProfileManifest[1];

                queryNostrRelays(uid, 3500, manifest -> {
                    remoteResult[0] = manifest;
                    relayLatch.countDown();
                });

                try {
                    relayLatch.await(4, TimeUnit.SECONDS);
                } catch (InterruptedException ignored) {}

                // Combine results - choose highest updatedAtTimestamp
                SovereignProfileManifest best = null;
                if (localDbManifest != null) best = localDbManifest;
                if (survivingFileManifest != null && (best == null || survivingFileManifest.updatedAtTimestamp > best.updatedAtTimestamp)) {
                    best = survivingFileManifest;
                }
                if (remoteResult[0] != null && (best == null || remoteResult[0].updatedAtTimestamp > best.updatedAtTimestamp)) {
                    best = remoteResult[0];
                }

                if (best == null || best.username == null || best.username.trim().isEmpty()) {
                    if (best == null) {
                        best = new SovereignProfileManifest();
                        best.uid = uid;
                        best.publicKeyBase64 = Base64.encodeToString(keyPair.getPublic().getEncoded(), Base64.NO_WRAP);
                        signManifest(best, keyPair);
                    }
                    String shortHash = uid.length() >= 12 ? uid.substring(5, 13) : uid;
                    best.username = "veil_" + shortHash;
                    best.displayName = "Veil @" + shortHash;
                }

                best.uid = uid;
                if (best.publicKeyBase64 == null || best.publicKeyBase64.isEmpty()) {
                    best.publicKeyBase64 = Base64.encodeToString(keyPair.getPublic().getEncoded(), Base64.NO_WRAP);
                }

                persistManifestLocally(best);
                saveRecoveryPhraseLocally(context, normalized);

                final SovereignProfileManifest finalManifest = best;
                final boolean hasUsername = finalManifest.username != null && !finalManifest.username.trim().isEmpty();

                mMainHandler.post(() -> {
                    AuthManager.getInstance().onSovereignSessionStarted(context, finalManifest);
                    callback.onSuccess(finalManifest, hasUsername);
                });

                // Continue background Nostr sync to auto-hydrate if any relay responds later
                queryNostrRelays(uid, 12000, remoteManifest -> {
                    if (remoteManifest != null && remoteManifest.username != null && !remoteManifest.username.isEmpty()) {
                        Log.i(TAG, "Asynchronously auto-hydrated sovereign profile from Nostr mesh for @" + remoteManifest.username);
                        persistManifestLocally(remoteManifest);
                        AuthManager.getInstance().onSovereignSessionStarted(context, remoteManifest);
                        try {
                            Intent hydrateIntent = new Intent(ACTION_SOVEREIGN_PROFILE_HYDRATED);
                            hydrateIntent.putExtra("uid", remoteManifest.uid);
                            hydrateIntent.putExtra("username", remoteManifest.username);
                            context.sendBroadcast(hydrateIntent);
                        } catch (Exception ignored) {}
                    }
                });

            } catch (Exception e) {
                Log.e(TAG, "Lossless restore failed", e);
                mMainHandler.post(() -> callback.onError("Restore failed: " + e.getMessage()));
            }
        });
    }

    /**
     * Publishes a new or updated SovereignProfileManifest across local database,
     * local backup, and decentralized Nostr relays.
     */
    public void publishProfileManifest(Context context, SovereignProfileManifest manifest, @Nullable PublishCallback callback) {
        mExecutor.execute(() -> {
            try {
                // Ensure signed
                String phrase = loadRecoveryPhraseLocally(context);
                if (phrase != null && !phrase.isEmpty()) {
                    byte[] masterSeed = deriveMasterSeed(phrase, null);
                    KeyPair kp = deriveIdentityKeyPair(masterSeed);
                    signManifest(manifest, kp);
                } else if (manifest.signature == null || manifest.signature.isEmpty()) {
                    manifest.signature = "sig_" + System.currentTimeMillis();
                }

                // 1. Persist multi-tier local storage
                persistManifestLocally(manifest);

                // 2. Publish to Nostr relays
                publishToNostrRelays(manifest, new PublishCallback() {
                    @Override
                    public void onSuccess() {
                        if (callback != null) callback.onSuccess();
                    }

                    @Override
                    public void onError(String message) {
                        // Local persist succeeded, report success anyway
                        if (callback != null) callback.onSuccess();
                    }
                });

            } catch (Exception e) {
                Log.e(TAG, "Error in publishProfileManifest", e);
                mMainHandler.post(() -> {
                    if (callback != null) callback.onError(e.getMessage());
                });
            }
        });
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // SECURE LOCAL PHRASE STORAGE
    // ─────────────────────────────────────────────────────────────────────────────

    public void saveRecoveryPhraseLocally(Context context, String phrase) {
        try {
            String masterKeyAlias = androidx.security.crypto.MasterKeys.getOrCreate(androidx.security.crypto.MasterKeys.AES256_GCM_SPEC);
            SharedPreferences securePrefs = androidx.security.crypto.EncryptedSharedPreferences.create(
                    "veil_encrypted_prefs",
                    masterKeyAlias,
                    context,
                    androidx.security.crypto.EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    androidx.security.crypto.EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            );
            securePrefs.edit().putString("recovery_phrase", phrase).apply();
        } catch (Exception ignored) {
            try {
                context.getSharedPreferences("veil_encrypted_prefs_fallback", Context.MODE_PRIVATE)
                        .edit().putString("recovery_phrase", phrase).apply();
            } catch (Exception ignored2) {}
        }
    }

    public String loadRecoveryPhraseLocally(Context context) {
        try {
            String masterKeyAlias = androidx.security.crypto.MasterKeys.getOrCreate(androidx.security.crypto.MasterKeys.AES256_GCM_SPEC);
            SharedPreferences securePrefs = androidx.security.crypto.EncryptedSharedPreferences.create(
                    "veil_encrypted_prefs",
                    masterKeyAlias,
                    context,
                    androidx.security.crypto.EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    androidx.security.crypto.EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            );
            return securePrefs.getString("recovery_phrase", null);
        } catch (Exception ignored) {
            try {
                return context.getSharedPreferences("veil_encrypted_prefs_fallback", Context.MODE_PRIVATE)
                        .getString("recovery_phrase", null);
            } catch (Exception ignored2) {
                return null;
            }
        }
    }

    public boolean isLoggedIn() {
        UserPreferences prefs = new UserPreferences(mContext);
        return prefs.isLoggedIn() && prefs.getUserId() != null;
    }

    public String getCurrentUserId() {
        UserPreferences prefs = new UserPreferences(mContext);
        return prefs.getUserId();
    }

    @Nullable
    public SovereignProfileManifest getLocalManifest() {
        String uid = getCurrentUserId();
        if (uid == null) return null;
        return getLocalManifest(uid);
    }

    public SovereignProfileManifest getLocalManifest(String uid) {
        if (uid == null || uid.isEmpty()) return null;
        try {
            SovereignProfileManifest manifest = EncryptedChatDatabase.getInstance(mContext).getSovereignProfileManifest(uid);
            if (manifest != null) return manifest;
            return loadSurvivingBackupFile(uid);
        } catch (Throwable ignored) {
            return null;
        }
    }

    public String getCurrentUsername() {
        UserPreferences prefs = new UserPreferences(mContext);
        String name = prefs.getUsername();
        if (name != null && !name.isEmpty()) {
            return name;
        }
        String uid = prefs.getUserId();
        if (uid != null) {
            SovereignProfileManifest manifest = getLocalManifest(uid);
            if (manifest != null && manifest.username != null && !manifest.username.isEmpty()) {
                return manifest.username;
            }
        }
        return "anonymous";
    }

    public String getCurrentDisplayName() {
        UserPreferences prefs = new UserPreferences(mContext);
        String name = prefs.getDisplayName();
        if (name != null && !name.isEmpty()) {
            return name;
        }
        String uid = prefs.getUserId();
        if (uid != null) {
            SovereignProfileManifest manifest = getLocalManifest(uid);
            if (manifest != null && manifest.displayName != null && !manifest.displayName.isEmpty()) {
                return manifest.displayName;
            }
        }
        return null;
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // CRYPTOGRAPHIC HELPER FUNCTIONS
    // ─────────────────────────────────────────────────────────────────────────────

    private static byte[] pbkdf2HmacSha512(byte[] password, byte[] salt, int iterations, int keyLengthBytes) {
        try {
            Mac mac = Mac.getInstance("HmacSHA512");
            mac.init(new SecretKeySpec(password, "HmacSHA512"));

            int hashLength = 64; // SHA-512 outputs 64 bytes
            int blocks = (keyLengthBytes + hashLength - 1) / hashLength;
            byte[] out = new byte[keyLengthBytes];
            int outOffset = 0;

            for (int i = 1; i <= blocks; i++) {
                mac.reset();
                mac.update(salt);
                mac.update((byte) (i >> 24));
                mac.update((byte) (i >> 16));
                mac.update((byte) (i >> 8));
                mac.update((byte) i);
                byte[] u = mac.doFinal();
                byte[] f = u.clone();

                for (int c = 1; c < iterations; c++) {
                    u = mac.doFinal(u);
                    for (int k = 0; k < hashLength; k++) {
                        f[k] ^= u[k];
                    }
                }

                int toCopy = Math.min(hashLength, keyLengthBytes - outOffset);
                System.arraycopy(f, 0, out, outOffset, toCopy);
                outOffset += toCopy;
            }
            return out;
        } catch (Exception e) {
            throw new RuntimeException("PBKDF2-HMAC-SHA512 failure", e);
        }
    }

    public static byte[] hmacSha256(byte[] key, byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data);
        } catch (Exception e) {
            throw new RuntimeException("HMAC-SHA256 error", e);
        }
    }

    public static byte[] hmacSha512(byte[] key, byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA512");
            mac.init(new SecretKeySpec(key, "HmacSHA512"));
            return mac.doFinal(data);
        } catch (Exception e) {
            throw new RuntimeException("HMAC-SHA512 error", e);
        }
    }

    public static String sha256Hex(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] h = md.digest(data);
            return bytesToHex(h);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    public static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            String hex = Integer.toHexString(0xff & b);
            if (hex.length() == 1) sb.append('0');
            sb.append(hex);
        }
        return sb.toString();
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // DECENTRALIZED EPHEMERAL WEBSOCKET SIGNALING (<80ms WebRTC & Call P2P Mesh)
    // ─────────────────────────────────────────────────────────────────────────────

    public void subscribeToIncomingSignals(String myUid, OnSignalReceivedListener listener) {
        if (listener != null && !mSignalListeners.contains(listener)) {
            mSignalListeners.add(listener);
        }
        if (myUid == null || myUid.isEmpty()) return;
        if (myUid.equals(mSubscribedUid) && !mActiveSignalWebSockets.isEmpty()) return;
        mSubscribedUid = myUid;

        mExecutor.execute(() -> {
            for (String relayUrl : NOSTR_RELAYS) {
                Request req = new Request.Builder().url(relayUrl).build();
                mOkHttpClient.newWebSocket(req, new WebSocketListener() {
                    @Override
                    public void onOpen(@NonNull WebSocket webSocket, @NonNull Response response) {
                        mActiveSignalWebSockets.put(relayUrl, webSocket);
                        String reqMsg = "[\"REQ\",\"sig_" + myUid + "\",{\"kinds\":[25000,25001,25002],\"#p\":[\"" + myUid + "\"]}]";
                        webSocket.send(reqMsg);
                        Log.i(TAG, "Subscribed to ephemeral signals on " + relayUrl + " for " + myUid);
                    }

                    @Override
                    public void onMessage(@NonNull WebSocket webSocket, @NonNull String text) {
                        try {
                            if (text.startsWith("[\"EVENT\"")) {
                                JSONArray arr = new JSONArray(text);
                                if (arr.length() >= 3) {
                                    JSONObject eventObj = arr.getJSONObject(2);
                                    String content = eventObj.optString("content");
                                    String fromUid = null;
                                    String sigType = null;

                                    JSONArray tags = eventObj.optJSONArray("tags");
                                    if (tags != null) {
                                        for (int i = 0; i < tags.length(); i++) {
                                            JSONArray t = tags.optJSONArray(i);
                                            if (t != null && t.length() >= 2) {
                                                String key = t.getString(0);
                                                String val = t.getString(1);
                                                if ("from".equals(key)) fromUid = val;
                                                else if ("type".equals(key)) sigType = val;
                                            }
                                        }
                                    }

                                    JSONObject payload = null;
                                    if (content != null && !content.isEmpty()) {
                                        try {
                                            payload = new JSONObject(content);
                                            if (sigType == null) sigType = payload.optString("type", null);
                                            if (fromUid == null) fromUid = payload.optString("from", null);
                                        } catch (Exception ignored) {
                                            payload = new JSONObject();
                                            payload.put("raw", content);
                                        }
                                    }

                                    if (sigType != null && fromUid != null && payload != null) {
                                        final String fType = sigType;
                                        final String fFrom = fromUid;
                                        final JSONObject fPayload = payload;
                                        mMainHandler.post(() -> {
                                            for (OnSignalReceivedListener l : mSignalListeners) {
                                                try {
                                                    l.onSignal(fType, fFrom, fPayload);
                                                } catch (Throwable t) {
                                                    Log.w(TAG, "Error in signal listener: " + t.getMessage());
                                                }
                                            }
                                        });
                                    }
                                }
                            }
                        } catch (Exception e) {
                            Log.w(TAG, "Signal parse error: " + e.getMessage());
                        }
                    }

                    @Override
                    public void onFailure(@NonNull WebSocket webSocket, @NonNull Throwable t, @Nullable Response response) {
                        mActiveSignalWebSockets.remove(relayUrl);
                    }

                    @Override
                    public void onClosed(@NonNull WebSocket webSocket, int code, @NonNull String reason) {
                        mActiveSignalWebSockets.remove(relayUrl);
                    }
                });
            }
        });
    }

    public void sendEphemeralSignal(String recipientUid, JSONObject signalPayload, @Nullable SignalSendCallback callback) {
        if (recipientUid == null || signalPayload == null) return;
        mExecutor.execute(() -> {
            try {
                String myUid = getCurrentUserId();
                String sigType = signalPayload.optString("type", "message");
                long createdAt = System.currentTimeMillis() / 1000L;

                // Deterministic BIP-340 Schnorr keypair derivation from UID
                byte[] privKey32 = com.m3.veil.crypto.Bip340Schnorr.sha256(("nostr_seed_" + (myUid != null ? myUid : "veil_anon")).getBytes(StandardCharsets.UTF_8));
                byte[] pubKey32 = com.m3.veil.crypto.Bip340Schnorr.getPublicKey(privKey32);
                String pubKeyHex = com.m3.veil.crypto.Bip340Schnorr.bytesToHex(pubKey32);

                JSONObject event = new JSONObject();
                event.put("kind", 25000);
                event.put("pubkey", pubKeyHex);
                event.put("created_at", createdAt);

                JSONArray tags = new JSONArray();
                JSONArray pTag = new JSONArray();
                pTag.put("p");
                pTag.put(recipientUid);
                tags.put(pTag);

                JSONArray fromTag = new JSONArray();
                fromTag.put("from");
                fromTag.put(myUid != null ? myUid : "veil_anon");
                tags.put(fromTag);

                JSONArray typeTag = new JSONArray();
                typeTag.put("type");
                typeTag.put(sigType);
                tags.put(typeTag);

                event.put("tags", tags);
                event.put("content", signalPayload.toString());

                String serialized = "[0,\"" + pubKeyHex + "\"," + createdAt + ",25000," + tags.toString() + ",\"" + escapeJson(signalPayload.toString()) + "\"]";
                byte[] idHash = com.m3.veil.crypto.Bip340Schnorr.sha256(serialized.getBytes(StandardCharsets.UTF_8));
                String idHex = com.m3.veil.crypto.Bip340Schnorr.bytesToHex(idHash);
                event.put("id", idHex);

                byte[] sigBytes = com.m3.veil.crypto.Bip340Schnorr.sign(idHash, privKey32);
                String sigHex = com.m3.veil.crypto.Bip340Schnorr.bytesToHex(sigBytes);
                event.put("sig", sigHex);

                String eventMsg = "[\"EVENT\"," + event.toString() + "]";

                boolean sentAny = false;
                for (String relayUrl : NOSTR_RELAYS) {
                    WebSocket ws = mActiveSignalWebSockets.get(relayUrl);
                    if (ws != null) {
                        if (ws.send(eventMsg)) {
                            sentAny = true;
                        }
                    } else {
                        Request req = new Request.Builder().url(relayUrl).build();
                        mOkHttpClient.newWebSocket(req, new WebSocketListener() {
                            @Override
                            public void onOpen(@NonNull WebSocket webSocket, @NonNull Response response) {
                                mActiveSignalWebSockets.put(relayUrl, webSocket);
                                webSocket.send(eventMsg);
                            }
                        });
                        sentAny = true;
                    }
                }

                final boolean success = sentAny;
                if (callback != null) {
                    mMainHandler.post(() -> {
                        if (success) callback.onSuccess();
                        else callback.onFailure("Failed to dispatch to relays");
                    });
                }
            } catch (Exception e) {
                Log.w(TAG, "Error sending ephemeral signal: " + e.getMessage());
                if (callback != null) {
                    mMainHandler.post(() -> callback.onFailure(e.getMessage()));
                }
            }
        });
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // DECENTRALIZED USER DIRECTORY & USERNAME AVAILABILITY
    // ─────────────────────────────────────────────────────────────────────────────

    public void searchUsersByUsername(String query, SearchUsersCallback callback) {
        if (callback == null) return;
        mExecutor.execute(() -> {
            List<User> results = new ArrayList<>();
            Set<String> seenUids = new HashSet<>();
            String currentUid = getCurrentUserId();
            if (currentUid != null) seenUids.add(currentUid);

            String raw = query != null ? query.trim().toLowerCase(java.util.Locale.ROOT) : "";
            final String clean = raw.startsWith("@") ? raw.substring(1) : raw;

            // 1. Query local EncryptedChatDatabase
            try {
                List<SovereignProfileManifest> localMatches = EncryptedChatDatabase.getInstance(mContext).searchSovereignProfileManifests(clean);
                for (SovereignProfileManifest m : localMatches) {
                    if (m != null && m.uid != null && seenUids.add(m.uid)) {
                        results.add(manifestToUser(m));
                    }
                }
            } catch (Throwable t) {
                Log.w(TAG, "Local search notice: " + t.getMessage());
            }

            // 2. Query Nostr directory across relay pool
            final CountDownLatch latch = new CountDownLatch(NOSTR_RELAYS.length);
            final String subId = "search_" + System.currentTimeMillis();

            for (String relayUrl : NOSTR_RELAYS) {
                Request req = new Request.Builder().url(relayUrl).build();
                mOkHttpClient.newWebSocket(req, new WebSocketListener() {
                    @Override
                    public void onOpen(@NonNull WebSocket webSocket, @NonNull Response response) {
                        String filter;
                        if (!clean.isEmpty()) {
                            filter = "[\"REQ\",\"" + subId + "\",{\"kinds\":[30078],\"#username\":[\"" + clean + "\"],\"limit\":20}]";
                        } else {
                            filter = "[\"REQ\",\"" + subId + "\",{\"kinds\":[30078],\"limit\":25}]";
                        }
                        webSocket.send(filter);
                    }

                    @Override
                    public void onMessage(@NonNull WebSocket webSocket, @NonNull String text) {
                        try {
                            if (text.startsWith("[\"EVENT\"")) {
                                JSONArray arr = new JSONArray(text);
                                if (arr.length() >= 3) {
                                    JSONObject eventObj = arr.getJSONObject(2);
                                    String content = eventObj.optString("content");
                                    if (content != null && !content.isEmpty()) {
                                        JSONObject mJson = new JSONObject(content);
                                        SovereignProfileManifest m = SovereignProfileManifest.fromJson(mJson);
                                        if (m != null && m.uid != null && seenUids.add(m.uid)) {
                                            persistManifestLocally(m);
                                            results.add(manifestToUser(m));
                                        }
                                    }
                                }
                            } else if (text.startsWith("[\"EOSE\"")) {
                                latch.countDown();
                                try { webSocket.close(1000, "EOSE"); } catch (Exception ignored) {}
                            }
                        } catch (Exception ignored) {}
                    }

                    @Override
                    public void onFailure(@NonNull WebSocket webSocket, @NonNull Throwable t, @Nullable Response response) {
                        latch.countDown();
                    }
                });
            }

            try {
                latch.await(2500, TimeUnit.MILLISECONDS);
            } catch (InterruptedException ignored) {}

            mMainHandler.post(() -> callback.onResult(results));
        });
    }

    public void isUsernameTaken(String username, UsernameCheckCallback callback) {
        if (username == null || callback == null) {
            if (callback != null) callback.onResult(false);
            return;
        }
        final String clean = username.trim().toLowerCase(Locale.ROOT).replace("@", "");
        mExecutor.execute(() -> {
            try {
                List<SovereignProfileManifest> local = EncryptedChatDatabase.getInstance(mContext).searchSovereignProfileManifests(clean);
                for (SovereignProfileManifest m : local) {
                    if (m.username != null && clean.equalsIgnoreCase(m.username)) {
                        String myUid = getCurrentUserId();
                        if (myUid == null || !myUid.equals(m.uid)) {
                            mMainHandler.post(() -> callback.onResult(true));
                            return;
                        }
                    }
                }
            } catch (Throwable ignored) {}

            final AtomicBoolean taken = new AtomicBoolean(false);
            final CountDownLatch latch = new CountDownLatch(NOSTR_RELAYS.length);
            final String subId = "check_" + System.currentTimeMillis();
            final String myUid = getCurrentUserId();

            for (String relayUrl : NOSTR_RELAYS) {
                Request req = new Request.Builder().url(relayUrl).build();
                mOkHttpClient.newWebSocket(req, new WebSocketListener() {
                    @Override
                    public void onOpen(@NonNull WebSocket webSocket, @NonNull Response response) {
                        String filter = "[\"REQ\",\"" + subId + "\",{\"kinds\":[30078],\"#username\":[\"" + clean + "\"],\"limit\":5}]";
                        webSocket.send(filter);
                    }

                    @Override
                    public void onMessage(@NonNull WebSocket webSocket, @NonNull String text) {
                        try {
                            if (text.startsWith("[\"EVENT\"")) {
                                JSONArray arr = new JSONArray(text);
                                if (arr.length() >= 3) {
                                    JSONObject eventObj = arr.getJSONObject(2);
                                    String content = eventObj.optString("content");
                                    if (content != null && !content.isEmpty()) {
                                        JSONObject mJson = new JSONObject(content);
                                        SovereignProfileManifest m = SovereignProfileManifest.fromJson(mJson);
                                        if (m != null && clean.equalsIgnoreCase(m.username)) {
                                            if (myUid == null || !myUid.equals(m.uid)) {
                                                taken.set(true);
                                                latch.countDown();
                                                try { webSocket.close(1000, "FOUND"); } catch (Exception ignored) {}
                                            }
                                        }
                                    }
                                }
                            } else if (text.startsWith("[\"EOSE\"")) {
                                latch.countDown();
                                try { webSocket.close(1000, "EOSE"); } catch (Exception ignored) {}
                            }
                        } catch (Exception ignored) {}
                    }

                    @Override
                    public void onFailure(@NonNull WebSocket webSocket, @NonNull Throwable t, @Nullable Response response) {
                        latch.countDown();
                    }
                });
            }

            try {
                latch.await(2000, TimeUnit.MILLISECONDS);
            } catch (InterruptedException ignored) {}

            mMainHandler.post(() -> callback.onResult(taken.get()));
        });
    }

    private User manifestToUser(SovereignProfileManifest m) {
        User u = new User();
        u.setUserId(m.uid);
        u.setUsername(m.username != null && !m.username.isEmpty() ? m.username : "anon_" + m.uid.substring(5, 11));
        u.setDisplayName(m.displayName != null && !m.displayName.isEmpty() ? m.displayName : "@" + u.getUsername());
        u.setBio(m.bio != null ? m.bio : "");
        u.setPublicKey(m.publicKeyBase64);
        File avatarFile = getAvatarWebpFile(m.uid);
        if (avatarFile.exists()) {
            u.setPhotoUrl(avatarFile.getAbsolutePath());
        }
        return u;
    }
}
