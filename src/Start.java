import java.rmi.RemoteException;

import javax.swing.*;
import javax.swing.plaf.TableHeaderUI;
import javax.xml.ws.Holder;
import java.rmi.NotBoundException;
import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.IntStream;


interface EnemyInfoReceiverInterface {
    void enemySpotted(GameInterface.PositionAndCourse enemyPositionAndCourse);
}

interface ShipSunkReceiverInterface {
    void shipSunked(Ship ship);
}

class TargetInfo {
    public TargetInfo(GameInterface.Position position, long time) {
        this.position = position;
        this.time = time;
    }

    public GameInterface.Position position;
    long time;
}

class Board {
    public Board(int width, int height) {
        this.width = width;
        this.height = height;
    }

    public int shipsAvailable() {
        int n = 0;
        for (Ship s : ships.values())
            if (s.alive())
                ++n;
        return n;
    }

    public boolean posOnBoard(GameInterface.Position position) {
        int x = position.getCol();
        int y = position.getRow();
        if (y < 0 || y >= height || x < 0 || x >= width)
            return false;

        return true;
    }

    public boolean isOnEdge(GameInterface.Position position) {
        int x = position.getCol();
        int y = position.getRow();
        if (y == 0 || y == height - 1 || x == 0 || x == width - 1)
            return true;

        return false;
    }

    void add(Ship ship) {
        ships.put(ship.getWarshipID(), ship);
    }
    Ship get(int warshipID) {
        return ships.get(warshipID);
    }
    Collection<Ship> getShips() {
        return ships.values();
    }

    private int width;
    private int height;

    private Map<Integer, Ship> ships = new HashMap<>();
}


class Ship extends Thread {
    public static Random generator = new Random();

    public enum Role {
        Explorer,
        Shooter,
        Sunked
    }

    public enum State {
        Normal,
        Idle
    }

    public static long maxIdleTime = GameInterface.MOVE_DELAY_HALF * 12 + GameInterface.FIRE_DELAY_HALF;
    public static long targetOutOfDateTime = GameInterface.MOVE_DELAY_HALF * 3;

    public Ship(TomBiCapitan tomBiCapitan, int warshipID, GameInterface.Position pos, GameInterface.Course course) {
        this.tomBiCapitan = tomBiCapitan;
        this.warshipID = warshipID;
        this.pos = pos;
        this.course = course;
        idleStartTime = 0;

        role = Role.Shooter;
        state = State.Normal;
        alive = true;

        targetPositionsQueue = this.tomBiCapitan.getTargetPositionsQueue();
        startTime = System.currentTimeMillis();
        startStormTime = startTime + GameInterface.THE_LULL_BEFORE_THE_STORM;
    }

    void registerSunkReceiver(ShipSunkReceiverInterface shipSunkReceiver) {
        this.shipSunkReceiver = shipSunkReceiver;
    }

    public void checkWithServer() throws RemoteException {
        String p = checkPosition();
        String c = checkCourse();
        if (p.length() > 0)
            pstr(p);
        if (c.length() > 0)
            pstr(c);
    }

    public String checkPosition() throws RemoteException {
        GameInterface game = tomBiCapitan.getGame();
        GameInterface.Position serverPos = game.getPosition(tomBiCapitan.getPlayerID(), warshipID);

        if (pos.equals(serverPos) == true)
            return new String();
//            return " - server has the same position " + serverPos;


        return " - server has different position " + serverPos;
    }

    public String checkCourse() throws RemoteException {
        GameInterface game = tomBiCapitan.getGame();
        GameInterface.Course serverCourse = game.getCourse(tomBiCapitan.getPlayerID(), warshipID);
        if (course.equals(serverCourse) == true)
            return new String();
//            return " - server has the same course " + serverCourse.fullCourseName();

        return " - server has different course " + serverCourse.fullCourseName();
    }

    public void run() {
        pstr();
        try {
            checkWithServer();
        } catch (RemoteException e) {
            e.printStackTrace();
        }

        while (alive) {
            long currTime = System.currentTimeMillis();
            try {
                update(currTime);
            } catch (RemoteException e) {
                // e.printStackTrace();
                pstr("- probably ship had been killed :( (" + e.getMessage() + ")");
                alive = false;
                setRole(Role.Sunked);
                shipSunkReceiver.shipSunked(this);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }

//            try {
//                sleep(1000);
//            } catch (InterruptedException e) {
//                e.printStackTrace();
//            }
        }
    }

    long prevTime = System.currentTimeMillis();

    void update(long currTime) throws RemoteException, InterruptedException {
        pstr(" :: Update dt = " + (currTime - prevTime));
        prevTime = currTime;

        if (isStormTime(currTime) == false) {
//            System.out.println("Storm time == false");
            // idz jak najbardziej na północ
            if (getCourse() == GameInterface.Course.NORTH)
                if (tomBiCapitan.isMovePossible(this))
                    move();
                else
                    pstr(" - cannot move because other ship position or next move");
            else if (course == GameInterface.Course.WEST)
                turnRight();
            else
                turnLeft();
        }

        if (getRole() == Role.Explorer) {
            tryFire(currTime);

            if (getCourse() == GameInterface.Course.NORTH) {
                switch (state) {
                    case Normal:
                        if (tomBiCapitan.getBoard().isOnEdge(getCourse().next(getPos()))) {
                            pstr(" - one step to edge, changing direction");
                            if (Ship.generator.nextInt(2) == 0)
                                turnLeft();
                            else
                                turnRight();
                        } else
                            move();
                        idleStartTime = currTime;
                        setState(State.Idle);
                        break;
                    case Idle:
                        if (currTime > idleStartTime + Ship.maxIdleTime) {
                            pstr("- byl w idle przez " + (currTime - idleStartTime) + " max(" + Ship.maxIdleTime + ")");
                            setState(State.Normal);
                            return;
                        } else {
                            // jesli malo statkow to Explorer w stanie Idle chce strzelać
                            if (tomBiCapitan.getBoard().shipsAvailable() < 4) {
                                pstr(" - EXP shooting ...");
                                tryFire(currTime);
                            }
                        }
                }
            }
            else if (course == GameInterface.Course.WEST)
                turnRight();
            else
                turnLeft();
        } else if (getRole() == Role.Shooter) {
            tryFire(currTime);
        }

        if (state == State.Idle)
            this.join(10);

        if (alive() == false || getRole() == Role.Sunked)
            return;
//        else
//            checkWithServer();
    }

    public void pstr() {
        System.out.println("" + getRole().name() + " ship(" + warshipID + ", " + course.fullCourseName() + ", " + pos + ")");
    }
    public void pstr(String str) {
        System.out.println("" + getRole().name() + " ship(" + warshipID + ", " + course.fullCourseName() + ", " + pos + ")" + str);
    }

    public int getWarshipID() {
        return warshipID;
    }
    public GameInterface.Position getPos() {
        return pos;
    }
    public GameInterface.Course getCourse() {
        return course;
    }
    public void setRole(Role role) {
        //pstr(" - set new role to " + role.name());
        this.role = role;
    }
    public Role getRole() {
        return role;
    }

    public void setState(State state) {
        //pstr(" - new state " + state.name() + " at time " + System.currentTimeMillis());
        this.state = state;
    }

    public boolean alive() {
        return alive;
    }
    public boolean isStormTime(long currTime) {
        return currTime >= startStormTime;
    }

    public void move() throws RemoteException {
//        pstr(" - before move" + checkPosition());

        GameInterface game = tomBiCapitan.getGame();

        game.move(tomBiCapitan.getPlayerID(), warshipID);
        try {
            this.join(GameInterface.MOVE_DELAY_HALF);
            pstr(" - moved from " + pos + " to " + course.next(pos));
            pos = course.next(pos);
            this.join(GameInterface.MOVE_DELAY_HALF);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

//        pstr(" - after move" + checkPosition());
    }
    public void turnLeft() throws RemoteException {
//        pstr(" - before TL" + checkCourse());

        GameInterface game = tomBiCapitan.getGame();

        game.turnLeft(tomBiCapitan.getPlayerID(), warshipID);
        try {
            this.join(GameInterface.TURN_DELAY_HALF);
            pstr(" - turn left. New course " + course.afterTurnToLeft().fullCourseName());
            course = course.afterTurnToLeft();
            this.join(GameInterface.TURN_DELAY_HALF);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
//        pstr(" - after TL" + checkCourse());
    }
    public void turnRight() throws RemoteException {
//        pstr(" - before TR" + checkCourse());

        GameInterface game = tomBiCapitan.getGame();

        game.turnRight(tomBiCapitan.getPlayerID(), warshipID);
        try {
            this.join(GameInterface.TURN_DELAY_HALF);
            pstr(" - turn right. New course " + course.afterTurnToRight().fullCourseName());
            course = course.afterTurnToRight();
            this.join(GameInterface.TURN_DELAY_HALF);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

//        pstr(" - after TR" + checkCourse());
    }
    public void fire(GameInterface.Position target) throws RemoteException {
//        pstr(" - fire before pos" + checkPosition());
//        pstr(" - fire before course" + checkCourse());

        GameInterface game = tomBiCapitan.getGame();

        game.fire(tomBiCapitan.getPlayerID(), warshipID, target);
        try {
            this.join(GameInterface.FIRE_DELAY_HALF);
            pstr(" - fired to target at " + target + ". Targets left: " + targetPositionsQueue.size());
            this.join(GameInterface.FIRE_DELAY_HALF);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

//        pstr(" - fire after pos" + checkPosition());
//        pstr(" - fire after course" + checkCourse());
    }

    private void tryFire(long currTime) throws InterruptedException, RemoteException {
        TargetInfo target = targetPositionsQueue.poll(10, TimeUnit.MILLISECONDS);
        if (target == null)
            return;
        else if (currTime - target.time > targetOutOfDateTime) {
            pstr("- no shooting(" + targetPositionsQueue.size() + " left) to " + target.position + " too much time took: " + (currTime - target.time) + " max(" + targetOutOfDateTime + ")");
            return;
        }

        pstr(" - Start fire to " + target.position + " with delay = " + (currTime - target.time));
        fire(target.position);
    }

    TomBiCapitan tomBiCapitan;
    int warshipID;
    private GameInterface.Position pos;
    private GameInterface.Course course;
    long idleStartTime;

    private Role role;
    private State state;
    private boolean alive;

    BlockingDeque<TargetInfo> targetPositionsQueue;
    ShipSunkReceiverInterface shipSunkReceiver;
    long startTime;
    long startStormTime;
}

class EnemyWatcher extends Thread {
    public EnemyWatcher(long playerID, GameInterface game) {
        this.playerID = playerID;
        this.game = game;
    }

    void registerReceiver(EnemyInfoReceiverInterface enemyInfoReceiver) {
        this.enemyInfoReceiver = enemyInfoReceiver;
    }

    public void run() {
        while (true) {
            try {
                enemyInfoReceiver.enemySpotted(game.getMessage(playerID));
            } catch (RemoteException e) {
//                e.printStackTrace();
            }
        }
    }

    private long playerID;
    private GameInterface game;
    EnemyInfoReceiverInterface enemyInfoReceiver;
}

class TomBiCapitan implements EnemyInfoReceiverInterface, ShipSunkReceiverInterface {
    private long playerID;
    private GameInterface game;

    public static int numOfExplorers = 2;


    public TomBiCapitan(long playerID, GameInterface game) throws RemoteException {
        this.playerID = playerID;
        this.game = game;
        board = new Board(GameInterface.WIDTH, GameInterface.HIGHT);

        enemyWatcher = new EnemyWatcher(this.playerID, this.game);
        enemyWatcher.registerReceiver(this);

        int numOfShips = game.getNumberOfAvaiablewarships(playerID);
        for (int i = 0; i < numOfShips; ++i) {
            Ship ship = new Ship(this, i, game.getPosition(playerID, i), game.getCourse(playerID, i));
            ship.registerSunkReceiver(this);
            board.add(ship);
        }

        findAndSetExplolers(numOfExplorers);

        for (Ship s : board.getShips())
            s.start();

        enemyWatcher.start();
    }

    public GameInterface getGame() {
        return game;
    }
    public Board getBoard() {
        return board;
    }
    public long getPlayerID() {
        return playerID;
    }
    public BlockingDeque<TargetInfo> getTargetPositionsQueue() {
        return targetPositionsQueue;
    }


    public void findAndSetExplolers(int n) {
        if (n > board.shipsAvailable()) {
            System.out.println("Uwaga: TBC::findExplolers(" + n + ") pozostało za mało statków: " + board.shipsAvailable());
            n = board.shipsAvailable();
        }

        int currNumOfExplorers = 0;
        for (Ship s : board.getShips())
            if (s.getRole() == Ship.Role.Explorer && s.alive())
                ++currNumOfExplorers;

        n -= currNumOfExplorers;

        int found = 0;
        while (found < n) {
            Integer max = null;
            Integer maxWarshipID = null;

            for (Ship s : board.getShips()) {
                if (s.getRole() == Ship.Role.Shooter) {
                    int sy = s.getPos().getRow();

                    if (max == null) {
                        max = sy;
                        maxWarshipID = s.getWarshipID();
                    } else if (sy > max) {
                        max = sy;
                        maxWarshipID = s.getWarshipID();
                    }
                }
            }

            if (maxWarshipID == null) {
                System.out.println("Error: Nie mozna zmienic roli statku na " + Ship.Role.Explorer.name() );
                return;
            }
            board.get(maxWarshipID).setRole(Ship.Role.Explorer);
            ++found;
        }
    }

    public boolean isMovePossible(Ship ship) {
        GameInterface.Position movePosition = ship.getCourse().next(ship.getPos());

        for (Ship s : board.getShips()) {
            if (s.getWarshipID() == ship.getWarshipID())
                continue;

            GameInterface.Position sPos = s.getPos();
            if (sPos.equals(movePosition))
                return false;
            else if (s.getCourse().next(sPos).equals(movePosition))
                return false;
        }

        return true;
    }

    @Override
    public void shipSunked(Ship ship) {
//        try {
//            ship.join();
//        } catch (InterruptedException e) {
//            e.printStackTrace();
//        }
        findAndSetExplolers(numOfExplorers);
    }

    @Override
    public void enemySpotted(GameInterface.PositionAndCourse enemyPositionAndCourse) {
        System.out.println("TBC::enemySpotted(" + enemyPositionAndCourse + ")");

        GameInterface.Position p = enemyPositionAndCourse.getPosition();
        GameInterface.Course c = enemyPositionAndCourse.getCourse();

        long currTime = System.currentTimeMillis();
        long enemyWatchedTime = enemyPositionAndCourse.getTime();
        Long targetLastTimeInMap = enemiesTimeMap.get(p);
        if (targetLastTimeInMap == null) {
            enemiesTimeMap.put(p, enemyWatchedTime);
            calcAndAddTargetsPositions(p, c, enemyWatchedTime);
        } else if (currTime - enemyWatchedTime > Ship.targetOutOfDateTime) {
            enemiesTimeMap.put(p, enemyWatchedTime);
            calcAndAddTargetsPositions(p, c, enemyWatchedTime);
        } else {
            enemiesTimeMap.put(p, enemyWatchedTime);
        }
    }

    private void calcAndAddTargetsPositions(GameInterface.Position target, GameInterface.Course course, long time) {
        addTargetsPositionsHelper(new TargetInfo(target, time));
        addTargetsPositionsHelper(new TargetInfo(course.next(target), time));
        addTargetsPositionsHelper(new TargetInfo(course.afterTurnToLeft().next(target), time));
        addTargetsPositionsHelper(new TargetInfo(course.afterTurnToRight().next(target), time));
    }

    private void addTargetsPositionsHelper(TargetInfo targetInfo) {
        if (getBoard().posOnBoard(targetInfo.position))
            targetPositionsQueue.add(targetInfo);
    }

    public void run() throws RemoteException {
        do {

        } while (board.shipsAvailable() > 0);
    }

    private Board board;
    private EnemyWatcher enemyWatcher;
    private BlockingDeque<TargetInfo> targetPositionsQueue = new LinkedBlockingDeque<>();
    private ConcurrentMap<GameInterface.Position, Long> enemiesTimeMap = new ConcurrentHashMap<>();
}

class Start {
    public static void main(String[] argv) {
        System.out.println("Start...");
        for (String s : argv)
            System.out.println("   arg = " + s);

        long playerID;
        GameInterface game;
        String PLAYER_NAME = "Tomasz_Bijata";

        try {
            game = (GameInterface)LocateRegistry.getRegistry(argv[0]).lookup("GAME");
            playerID = game.register(PLAYER_NAME);

            System.out.println("Gracz " + PLAYER_NAME + " zarejestrowany ID " + playerID );

            game.waitForStart(playerID);

            TomBiCapitan player = new TomBiCapitan(playerID, game);
            player.run();

        } catch (RemoteException e) {
            e.printStackTrace();
        } catch (NotBoundException e) {
            e.printStackTrace();
        }

        System.out.println("... END");
    }
}
