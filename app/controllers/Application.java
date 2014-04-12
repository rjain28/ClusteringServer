package controllers;

import clustering.Cluster;
import clustering.LatLng;
import clustering.LocationUtils;
import clustering.RandomLocationsGenerator;
import com.avaje.ebean.Ebean;
import com.avaje.ebean.Page;
import models.Location;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import play.libs.Json;
import play.mvc.Controller;
import play.mvc.Result;
import views.html.list;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;

import static clustering.QuadTile.getTileFromQuadKey;
import static clustering.ZoomLevel.Z19;


public class Application extends Controller {

    private static Logger log = LoggerFactory.getLogger(Application.class);

    /**
     * This result directly redirect to application home.
     */
    public static Result GO_HOME = redirect(
            routes.Application.list(0, "name", "asc", "")
    );

    /**
     * Handle default path requests, redirect to computers list
     */
    public static Result index() {
        return GO_HOME;
    }

    /**
     * Display the paginated list of locations.
     *
     * @param page   Current listByName number (starts from 0)
     * @param sortBy Column to be sorted
     * @param order  Sort order (either asc or desc)
     * @param filter Filter applied on location names
     */
    public static Result list(int page, String sortBy, String order, String filter) {
        return ok(
                list.render(
                        Location.listByName(page, 10, sortBy, order, filter),
                        sortBy, order, filter
                )
        );
    }

    /**
     * Update db locations with random coordinates and computed quad key at the max zoom level.
     */
    public static Result update(LatLng sw, LatLng ne) {
        int page = 0;
        int totalSize = 0;
        Page<Location> result;
        do {
            result = Location.list(page, 1000);
            for (Location location : result.getList()) {
                LatLng latLng = RandomLocationsGenerator.generate(sw, ne);
                location.latitude = latLng.latitude;
                location.longitude = latLng.longitude;
                location.quadKey = LocationUtils.getQuadKey(latLng, Z19);
            }
            Ebean.save(result.getList());
            page++;
            totalSize += result.getList().size();
        } while (result.getList().size() > 0);

        return ok("Updated the quad keys for " + totalSize + " locations.");
    }

    /**
     * Show Google Maps centered on a default location.
     */
    public static Result map() {
        return ok(views.html.map.render());
    }


    /**
     * Return the JSON representation for all clusters found within the given bounds
     * at the specified zoom level.
     *
     * @param sw   south west bound {@link LatLng}
     * @param ne   north east bound {@link LatLng}
     * @param zoom the zoom level of the map.
     * @return
     */
    public static Result jsonList(LatLng sw, LatLng ne, int zoom) {
        try {
            log.info("Getting clusters in bounds {} - {} at zoom {}", sw, ne, zoom);

            Map<String, Integer> clusterCount = getClustersCount(sw, ne, zoom);

            log.debug("Got back clusters map {}", clusterCount.toString());

            Map<String, Cluster> clusters = new HashMap<String, Cluster>();
            for (Map.Entry<String, Integer> entry : clusterCount.entrySet()) {
                if(entry.getValue() > 0) {
                    clusters.put(entry.getKey(), new Cluster(getTileFromQuadKey(entry.getKey()).center, entry.getValue()));
                }
            }

            return ok(Json.toJson(clusters));

        } catch (SQLException e) {
            return internalServerError(e.getMessage());
        }
    }

    private static Map<String, Integer> getClustersCount(LatLng sw, LatLng ne, int zoom) throws SQLException {
        // cqk = cluster quad key, cnt = how many locations in the cluster
        String sql = "SELECT SUBSTRING(quad_key, 1, ?) AS cqk, COUNT(*) AS cnt " +
                "FROM location " +
                "WHERE latitude > ? AND latitude < ? " +
                "AND longitude > ? AND longitude < ? " +
                "GROUP BY cqk";

        Connection conn = play.db.DB.getConnection();
        PreparedStatement stmt = null;
        try {
            stmt = conn.prepareStatement(sql);
            stmt.setInt(1, zoom);
            stmt.setDouble(2, sw.latitude);
            stmt.setDouble(3, ne.latitude);
            stmt.setDouble(4, sw.longitude);
            stmt.setDouble(5, ne.longitude);

            log.debug("Executing SQL {}", stmt.toString());

            ResultSet result = stmt.executeQuery();

            Map<String, Integer> clusters = new HashMap<String, Integer>();
            while (result.next()) {
                clusters.put(result.getString("cqk"), result.getInt("cnt"));
            }
            return clusters;
        } finally {
            try {
                if (stmt != null) {
                    stmt.close();
                }
                conn.close();
            } catch (SQLException e) {
                e.printStackTrace();
            }
        }
    }
}
