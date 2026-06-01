# Seat Alert Listener

Lightweight Java listener for running `SeatAlertWatcher` with the required dependency JAR.

## Requirements

- Java (JRE/JDK) installed and available on `PATH`

To download the dependency JAR:

- https://CheminotJWS.etsmtl.ca/ChemiNotC.jar

Place `ChemiNotC.jar` at:
	- `<PATH_TO_JAR>\\ChemiNotC.jar`

## Run

From this project folder, run:

```bat
java -cp ".;<PATH_TO_JAR>\\ChemiNotC.jar" SeatAlertWatcher
```

## Notes

- `.;` in the classpath includes the current directory.
- If the JAR path changes, update the classpath argument accordingly.
