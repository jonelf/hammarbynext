package se.hammarbynext.tv;

import java.util.Random;

/**
 * Planen: tre rutor (Hammarby, AIK och domaren) som studsar mot kanterna och mot varandra. Ren Java utan
 * Android, så den kan testas på datorn (PitchTest). Koordinater i pixlar, rutornas övre vänstra hörn.
 *
 * Hammarby mot AIK: en blixt, och ibland ramlar ett av lagen ner till botten och ligger en stund.
 * Ett lag mot domaren: ibland gult kort, ibland rött, och då åker laget ut och kommer tillbaka efter 30 s.
 * Mål: kortsidornas mitt (GOAL av höjden). Hammarby gör mål till höger, AIK till vänster.
 */
final class Pitch {

    enum Role { HAMMARBY, AIK, REFEREE }

    enum State { PLAY, FALLING, LYING, EJECTED, OUT }

    interface Listener {
        /** Hammarby och AIK krockade, vid (x, y). */
        void onClash(float x, float y);

        /** Domaren visade kort; red betyder att laget åker ut. */
        void onCard(Body team, Body referee, boolean red);

        /** Laget gjorde mål vid (x, y) på kortsidan. */
        void onGoal(Body team, float x, float y);
    }

    // --- Inställningar ---------------------------------------------------------------------------
    /** Fart i andel av skärmbredden per sekund. */
    static final float SPEED = 0.045f;
    /** Målets höjd i andel av skärmhöjden, mitt på kortsidan. */
    static final float GOAL = 0.15f;
    /** Andel av krockarna Hammarby–AIK där ett lag ramlar; delas lika mellan lagen. Resten studsar bara. */
    static final double P_FALL = 1.0 / 3;
    /** Lag mot domare: gult kort och rött kort (utvisning). Resten studsar bara. */
    static final double P_YELLOW = 0.40;
    static final double P_RED = 0.10;
    static final long LIE_MS = 6_000L;
    static final long OUT_MS = 30_000L;
    /** Samma par räknas som en ny krock först efter så här lång tid. */
    static final long COOLDOWN_MS = 800L;
    static final long GOAL_COOLDOWN_MS = 3_000L;
    /** Längsta tid en ruta får glida igenom de andra när den kommer in eller reser sig. */
    static final long GHOST_MS = 3_000L;

    static final class Body {
        final Role role;
        float x, y, w, h, vx, vy;
        /** Lutning i grader, och hur fort den ändras när laget ramlar eller åker ut. */
        float angle, spin, targetAngle;
        State state = State.PLAY;
        long until;
        /** Kommer in igen eller reser sig: krockar inte förrän den är fri från de andra. */
        boolean ghost;
        long ghostSince;
        /** EJECTED och OUT: åt vilket håll laget åkte ut, -1 vänster, 1 höger. */
        int side = 1;
        long lastGoal = Long.MIN_VALUE / 2;

        Body(Role role) {
            this.role = role;
        }

        float cx() {
            return x + w / 2;
        }

        float cy() {
            return y + h / 2;
        }

        boolean visible() {
            return state != State.OUT;
        }

        boolean team() {
            return role != Role.REFEREE;
        }

        /** Krockar med de andra: spelar (och är inte på väg in), eller ligger ner (då som ett hinder). */
        boolean solid() {
            return (state == State.PLAY && !ghost) || state == State.LYING;
        }
    }

    final Body[] bodies = {new Body(Role.HAMMARBY), new Body(Role.AIK), new Body(Role.REFEREE)};
    private final Random rnd;
    private final Listener listener;
    private final long[][] lastHit = new long[3][3];
    private float width, height, speed;
    private boolean placed;

    Pitch(Random rnd, Listener listener) {
        this.rnd = rnd;
        this.listener = listener;
        for (long[] row : lastHit) java.util.Arrays.fill(row, Long.MIN_VALUE / 2);
    }

    Body body(Role role) {
        return bodies[role.ordinal()];
    }

    float speed() {
        return speed;
    }

    /** Skärmens och rutornas storlek. Första gången placeras rutorna slumpvis utan att överlappa. */
    void setSize(float width, float height, float cardW, float cardH) {
        this.width = width;
        this.height = height;
        speed = width * SPEED;
        for (Body b : bodies) {
            b.w = cardW;
            b.h = cardH;
        }
        if (!placed) {
            place();
            placed = true;
        } else {
            for (Body b : bodies) {
                if (b.state == State.PLAY && !b.ghost) {
                    b.x = clamp(b.x, 0, width - b.w);
                    b.y = clamp(b.y, 0, height - b.h);
                }
            }
        }
    }

    private void place() {
        for (int i = 0; i < bodies.length; i++) {
            Body b = bodies[i];
            for (int attempt = 0; attempt < 200; attempt++) {
                b.x = rnd.nextFloat() * Math.max(0, width - b.w);
                b.y = rnd.nextFloat() * Math.max(0, height - b.h);
                boolean free = true;
                for (int j = 0; j < i; j++) free &= !overlap(b, bodies[j]);
                if (free) break;
            }
            randomVelocity(b);
        }
    }

    private void randomVelocity(Body b) {
        double a = Math.toRadians(25 + rnd.nextInt(40)) + rnd.nextInt(4) * Math.PI / 2;
        b.vx = (float) (Math.cos(a) * speed);
        b.vy = (float) (Math.sin(a) * speed);
    }

    /** Ett steg: dt i sekunder, now i millisekunder. */
    void step(float dt, long now) {
        if (width <= 0 || height <= 0) return;
        for (Body b : bodies) move(b, dt, now);
        for (int i = 0; i < bodies.length; i++) {
            for (int j = i + 1; j < bodies.length; j++) collide(i, j, now);
        }
        // Isärskjutningen kan trycka en ruta ut över kanten: tillbaka in, och vänd om den var på väg ut.
        for (Body b : bodies) {
            if (b.state != State.PLAY || b.ghost) continue;
            if (b.x < 0) { b.x = 0; b.vx = Math.abs(b.vx); }
            if (b.x + b.w > width) { b.x = width - b.w; b.vx = -Math.abs(b.vx); }
            if (b.y < 0) { b.y = 0; b.vy = Math.abs(b.vy); }
            if (b.y + b.h > height) { b.y = height - b.h; b.vy = -Math.abs(b.vy); }
        }
        for (int i = 0; i < bodies.length; i++) {
            Body b = bodies[i];
            if (!b.ghost || b.state != State.PLAY || !inside(b)) continue;
            if (!overlapsAny(b)) {
                b.ghost = false;
            } else if (now - b.ghostSince > GHOST_MS) {
                // Följs åt med en annan ruta: bli fast ändå, så skjuts de isär. Det räknas inte som en krock.
                b.ghost = false;
                for (int j = 0; j < bodies.length; j++) {
                    if (j != i) lastHit[Math.min(i, j)][Math.max(i, j)] = now;
                }
            }
        }
    }

    private void move(Body b, float dt, long now) {
        switch (b.state) {
            case PLAY:
                b.angle += (0 - b.angle) * Math.min(1f, dt * 3f);
                b.x += b.vx * dt;
                b.y += b.vy * dt;
                walls(b, now);
                keepSpeed(b, dt);
                break;
            case FALLING:
                b.vy += height * 3f * dt;
                b.vx *= (float) Math.exp(-2 * dt);
                b.x = clamp(b.x + b.vx * dt, 0, width - b.w);
                b.y += b.vy * dt;
                b.angle = approach(b.angle, b.targetAngle, Math.abs(b.spin) * dt);
                if (b.y + b.h >= height) {
                    b.y = height - b.h;
                    b.vx = b.vy = 0;
                    b.state = State.LYING;
                    b.until = now + LIE_MS;
                }
                break;
            case LYING:
                if (now >= b.until) {
                    b.state = State.PLAY;
                    b.ghost = true;
                    b.ghostSince = now;
                    b.vy = -speed;
                    b.vx = (rnd.nextBoolean() ? 1 : -1) * speed * 0.7f;
                }
                break;
            case EJECTED:
                b.vx += b.side * speed * 6f * dt;
                b.x += b.vx * dt;
                b.y += b.vy * dt;
                b.angle += b.spin * dt;
                if (b.x > width + 20 || b.x + b.w < -20) {
                    b.state = State.OUT;
                    b.until = now + OUT_MS;
                }
                break;
            case OUT:
                if (now >= b.until) {
                    // Tillbaka från samma sida som laget åkte ut.
                    b.state = State.PLAY;
                    b.ghost = true;
                    b.ghostSince = now;
                    b.angle = 0;
                    b.y = rnd.nextFloat() * Math.max(0, height - b.h);
                    b.x = b.side > 0 ? width : -b.w;
                    b.vx = -b.side * speed;
                    b.vy = (rnd.nextBoolean() ? 1 : -1) * speed * 0.6f;
                }
                break;
        }
    }

    /** Studsar bara mot en kant den rör sig mot, så en ruta som kommer in utifrån släpps in. */
    private void walls(Body b, long now) {
        if (b.x < 0 && b.vx < 0) {
            if (b.role == Role.AIK) goal(b, 0, now);
            b.x = -b.x;
            b.vx = -b.vx;
        } else if (b.x + b.w > width && b.vx > 0) {
            if (b.role == Role.HAMMARBY) goal(b, width, now);
            b.x = 2 * (width - b.w) - b.x;
            b.vx = -b.vx;
        }
        if (b.y < 0 && b.vy < 0) {
            b.y = -b.y;
            b.vy = -b.vy;
        } else if (b.y + b.h > height && b.vy > 0) {
            b.y = 2 * (height - b.h) - b.y;
            b.vy = -b.vy;
        }
    }

    private void goal(Body b, float wallX, long now) {
        if (Math.abs(b.cy() - height / 2) > GOAL * height / 2) return;
        if (now - b.lastGoal < GOAL_COOLDOWN_MS) return;
        b.lastGoal = now;
        listener.onGoal(b, wallX, height / 2);
    }

    /** Håller farten jämn efter krockar, och undviker banor som är nästan vågräta eller lodräta. */
    private void keepSpeed(Body b, float dt) {
        float s = (float) Math.hypot(b.vx, b.vy);
        if (s < 1e-3f) {
            randomVelocity(b);
            return;
        }
        float k = 1 + (speed / s - 1) * Math.min(1f, dt * 1.5f);
        b.vx *= k;
        b.vy *= k;
        float min = speed * 0.35f;
        if (Math.abs(b.vx) < min) b.vx = (b.vx < 0 ? -1 : 1) * min;
        if (Math.abs(b.vy) < min) b.vy = (b.vy < 0 ? -1 : 1) * min;
    }

    private void collide(int i, int j, long now) {
        Body a = bodies[i], b = bodies[j];
        if (!a.solid() || !b.solid()) return;
        boolean aMoves = a.state == State.PLAY, bMoves = b.state == State.PLAY;
        if (!aMoves && !bMoves) return;
        float ox = Math.min(a.x + a.w, b.x + b.w) - Math.max(a.x, b.x);
        float oy = Math.min(a.y + a.h, b.y + b.h) - Math.max(a.y, b.y);
        if (ox <= 0 || oy <= 0) return;

        float px = (Math.max(a.x, b.x) + Math.min(a.x + a.w, b.x + b.w)) / 2;
        float py = (Math.max(a.y, b.y) + Math.min(a.y + a.h, b.y + b.h)) / 2;
        boolean approaching;
        if (ox < oy) {
            int dir = a.cx() < b.cx() ? -1 : 1;   // a skjuts åt det här hållet
            approaching = (a.vx - b.vx) * dir < 0;
            separate(a, b, aMoves, bMoves, dir * ox, 0);
            if (approaching) {
                if (aMoves && bMoves) {
                    float t = a.vx; a.vx = b.vx; b.vx = t;
                } else if (aMoves) {
                    a.vx = -a.vx;
                } else {
                    b.vx = -b.vx;
                }
            }
        } else {
            int dir = a.cy() < b.cy() ? -1 : 1;
            approaching = (a.vy - b.vy) * dir < 0;
            separate(a, b, aMoves, bMoves, 0, dir * oy);
            if (approaching) {
                if (aMoves && bMoves) {
                    float t = a.vy; a.vy = b.vy; b.vy = t;
                } else if (aMoves) {
                    a.vy = -a.vy;
                } else {
                    b.vy = -b.vy;
                }
            }
        }
        if (approaching && aMoves && bMoves && now - lastHit[i][j] >= COOLDOWN_MS) {
            lastHit[i][j] = now;
            event(a, b, px, py);
        }
    }

    private static void separate(Body a, Body b, boolean aMoves, boolean bMoves, float dx, float dy) {
        if (aMoves && bMoves) {
            a.x += dx / 2; a.y += dy / 2;
            b.x -= dx / 2; b.y -= dy / 2;
        } else if (aMoves) {
            a.x += dx; a.y += dy;
        } else {
            b.x -= dx; b.y -= dy;
        }
    }

    private void event(Body a, Body b, float px, float py) {
        if (a.team() && b.team()) {
            listener.onClash(px, py);
            double r = rnd.nextDouble();
            if (r < P_FALL / 2) fall(body(Role.HAMMARBY));
            else if (r < P_FALL) fall(body(Role.AIK));
        } else {
            Body team = a.team() ? a : b, referee = a.team() ? b : a;
            double r = rnd.nextDouble();
            if (r < P_RED) {
                listener.onCard(team, referee, true);
                eject(team, team.cx() < referee.cx() ? -1 : 1);
            } else if (r < P_RED + P_YELLOW) {
                listener.onCard(team, referee, false);
            }
        }
    }

    private void fall(Body b) {
        int sign = rnd.nextBoolean() ? 1 : -1;
        b.state = State.FALLING;
        b.vy = -speed * 1.5f;   // ett litet hopp innan fallet
        b.vx *= 0.5f;
        b.targetAngle = sign * 14f;
        b.spin = sign * 90f;
    }

    private void eject(Body b, int side) {
        b.state = State.EJECTED;
        b.side = side;
        b.vx = side * speed * 2f;
        b.vy = -speed * 0.3f;
        b.spin = side * 240f;
    }

    private boolean inside(Body b) {
        return b.x >= 0 && b.y >= 0 && b.x + b.w <= width && b.y + b.h <= height;
    }

    private boolean overlapsAny(Body b) {
        for (Body o : bodies) {
            if (o != b && o.visible() && o.state != State.EJECTED && overlap(b, o)) return true;
        }
        return false;
    }

    static boolean overlap(Body a, Body b) {
        return a.x < b.x + b.w && b.x < a.x + a.w && a.y < b.y + b.h && b.y < a.y + a.h;
    }

    private static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static float approach(float v, float target, float step) {
        return v < target ? Math.min(target, v + step) : Math.max(target, v - step);
    }
}
