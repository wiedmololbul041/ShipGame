import java.rmi.RemoteException;

class Game implements GameInterface {
    public Game() {

    }

    @Override
    public long register(String playerName) throws RemoteException {
        return 0;
    }

    @Override
    public void waitForStart(long playerID) throws RemoteException {

    }

    @Override
    public int getNumberOfAvaiablewarships(long playerID) throws RemoteException {
        return 0;
    }

    @Override
    public Course getCourse(long playerID, int warshipID) throws RemoteException {
        return null;
    }

    @Override
    public Position getPosition(long playerID, int warshipID) throws RemoteException {
        return null;
    }

    @Override
    public boolean isAlive(long playerID, int warshipID) throws RemoteException {
        return false;
    }

    @Override
    public void turnLeft(long playerID, int warshipID) throws RemoteException {

    }

    @Override
    public void turnRight(long playerID, int warshipID) throws RemoteException {

    }

    @Override
    public void move(long playerID, int warshipID) throws RemoteException {

    }

    @Override
    public boolean fire(long playerID, int warshipID, Position target) throws RemoteException {
        return false;
    }

    @Override
    public PositionAndCourse getMessage(long playerID) throws RemoteException {
        return null;
    }
}

class Start {
    public static void main(String[] argv) {
        System.out.println("Start...");
        System.out.println("... END");
    }
}
