package org.acme;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * A row of the <code>${{ values.tableName }}</code> table.
 *
 * <p>The identifier uses IDENTITY generation so it lines up with the
 * <code>BIGSERIAL</code> column created by the GitOps database init job.
 */
@Entity
@Table(name = "${{ values.tableName }}")
public class Person extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @NotBlank(message = "first name is required")
    @Size(max = 100)
    @Column(name = "first_name", nullable = false, length = 100)
    public String firstName;

    @NotBlank(message = "second name is required")
    @Size(max = 100)
    @Column(name = "second_name", nullable = false, length = 100)
    public String secondName;

    @Min(value = 0, message = "age cannot be negative")
    @Max(value = 150, message = "age looks implausible")
    @Column(name = "age")
    public Integer age;

    @Size(max = 16)
    @Column(name = "postcode", length = 16)
    public String postcode;
}
