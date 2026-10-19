package org.acme;

import java.net.URI;
import java.util.List;

import jakarta.transaction.Transactional;
import jakarta.validation.Valid;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * CRUD over the <code>${{ values.tableName }}</code> table.
 */
@Path("/api/people")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class PersonResource {

    @GET
    public List<Person> list() {
        return Person.listAll();
    }

    @GET
    @Path("/{id}")
    public Person get(@PathParam("id") Long id) {
        Person person = Person.findById(id);
        if (person == null) {
            throw new WebApplicationException("No row with id " + id, Response.Status.NOT_FOUND);
        }
        return person;
    }

    @POST
    @Transactional
    public Response create(@Valid Person person) {
        person.id = null;
        person.persist();
        return Response.created(URI.create("/api/people/" + person.id)).entity(person).build();
    }

    @PUT
    @Path("/{id}")
    @Transactional
    public Person update(@PathParam("id") Long id, @Valid Person input) {
        Person person = Person.findById(id);
        if (person == null) {
            throw new WebApplicationException("No row with id " + id, Response.Status.NOT_FOUND);
        }
        person.firstName = input.firstName;
        person.secondName = input.secondName;
        person.age = input.age;
        person.postcode = input.postcode;
        return person;
    }

    @DELETE
    @Path("/{id}")
    @Transactional
    public Response delete(@PathParam("id") Long id) {
        if (!Person.deleteById(id)) {
            throw new WebApplicationException("No row with id " + id, Response.Status.NOT_FOUND);
        }
        return Response.noContent().build();
    }
}
