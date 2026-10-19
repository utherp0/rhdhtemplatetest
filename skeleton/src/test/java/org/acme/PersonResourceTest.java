package org.acme;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import io.quarkus.test.junit.QuarkusTest;

/**
 * Exercises the full create / read / update / delete cycle against the
 * <code>${{ values.tableName }}</code> table.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PersonResourceTest {

    private static Integer createdId;

    @Test
    @Order(1)
    void create() {
        createdId = given().contentType("application/json")
                .body("""
                        { "firstName": "Ada", "secondName": "Lovelace", "age": 36, "postcode": "1011AB" }
                        """)
                .post("/api/people")
                .then()
                .statusCode(201)
                .header("Location", notNullValue())
                .body("id", notNullValue())
                .body("firstName", equalTo("Ada"))
                .extract().path("id");
    }

    @Test
    @Order(2)
    void read() {
        given().get("/api/people/" + createdId)
                .then()
                .statusCode(200)
                .body("secondName", equalTo("Lovelace"))
                .body("age", is(36))
                .body("postcode", equalTo("1011AB"));

        given().get("/api/people")
                .then()
                .statusCode(200)
                .body("size()", is(1));
    }

    @Test
    @Order(3)
    void update() {
        given().contentType("application/json")
                .body("""
                        { "firstName": "Ada", "secondName": "Byron", "age": 37, "postcode": "3011AA" }
                        """)
                .put("/api/people/" + createdId)
                .then()
                .statusCode(200)
                .body("secondName", equalTo("Byron"))
                .body("age", is(37));
    }

    @Test
    @Order(4)
    void delete() {
        given().delete("/api/people/" + createdId).then().statusCode(204);
        given().get("/api/people/" + createdId).then().statusCode(404);
        given().get("/api/people").then().statusCode(200).body("size()", is(0));
    }

    @Test
    @Order(5)
    void rejectsInvalidInput() {
        given().contentType("application/json")
                .body("{ \"firstName\": \"\", \"secondName\": \"Nobody\", \"age\": -5 }")
                .post("/api/people")
                .then()
                .statusCode(400);
    }

    @Test
    @Order(6)
    void servesTheWebInterface() {
        given().get("/")
                .then()
                .statusCode(200)
                .contentType("text/html")
                .body(containsString("${{ values.tableName }}"))
                .body(containsString("/api/people"));
    }

    @Test
    @Order(7)
    void updateOfMissingRowIs404() {
        given().contentType("application/json")
                .body("{ \"firstName\": \"Ghost\", \"secondName\": \"Row\", \"age\": 1 }")
                .put("/api/people/999999")
                .then()
                .statusCode(404);
    }
}
