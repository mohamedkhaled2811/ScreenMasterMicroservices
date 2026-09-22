package com.gr74.catalog.controller;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.gr74.catalog.config.SecurityConfig;
import com.gr74.catalog.exception.MovieNotFoundException;
import com.gr74.catalog.model.Genre;
import com.gr74.catalog.model.Movie;
import com.gr74.catalog.service.MovieService;

/** Web slice for {@code GET /movies/{id}} with a mocked service. Reads are public (no token). */
@Import(SecurityConfig.class)
@WebMvcTest(MovieController.class)
class MovieControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private MovieService movieService;

    @Test
    void returnsMovieDtoWithGenres() throws Exception {
        Movie movie = new Movie(550L, "Fight Club");
        movie.addGenre(new Genre(18L, "Drama"));
        given(movieService.getById(550L)).willReturn(movie);

        mockMvc.perform(get("/movies/{id}", 550L))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.id").value(550))
                .andExpect(jsonPath("$.title").value("Fight Club"))
                .andExpect(jsonPath("$.genres[0].id").value(18))
                .andExpect(jsonPath("$.genres[0].name").value("Drama"));
    }

    @Test
    void returnsProblemDetailWhenMovieMissing() throws Exception {
        given(movieService.getById(999_999L)).willThrow(new MovieNotFoundException(999_999L));

        mockMvc.perform(get("/movies/{id}", 999_999L))
                .andExpect(status().isNotFound())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("CATALOG_MOVIE_NOT_FOUND"))
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    void returnsProblemDetailWhenIdNotANumber() throws Exception {
        mockMvc.perform(get("/movies/{id}", "not-a-number"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("CATALOG_VALIDATION_ERROR"));
    }
}
