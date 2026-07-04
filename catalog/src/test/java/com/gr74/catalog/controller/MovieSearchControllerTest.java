package com.gr74.catalog.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.gr74.catalog.config.WebPagingConfig;
import com.gr74.catalog.controller.dto.MovieFilter;
import com.gr74.catalog.exception.CatalogErrorCode;
import com.gr74.catalog.exception.CatalogException;
import com.gr74.catalog.model.Genre;
import com.gr74.catalog.model.Movie;
import com.gr74.catalog.service.MovieService;

/**
 * Web slice for the {@code GET /movies} dynamic filter — proves query-param binding, the paged JSON
 * envelope, and that both validation failure modes (bad sort, out-of-range param) render as a
 * {@code CATALOG_VALIDATION_ERROR} ProblemDetail. The {@link MovieService} is mocked, so this is
 * about the HTTP contract, not the query (that's covered by {@code MovieSpecificationsTest}).
 *
 * <p>Imports {@link WebPagingConfig} so the slice serializes the page as the stable {@code PagedModel}
 * envelope ({@code VIA_DTO}) — the same contract the running app uses — hence the assertions read the
 * nested {@code $.page.*} metadata, not the deprecated flat {@code $.totalElements}.
 */
@WebMvcTest(MovieController.class)
@Import(WebPagingConfig.class)
class MovieSearchControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private MovieService movieService;

    private Movie sampleMovie() {
        Movie movie = new Movie(603L, "The Matrix");
        movie.addGenre(new Genre(28L, "Action"));
        movie.applyDetails(b -> b
                .releaseDate(LocalDate.of(1999, 3, 31))
                .voteAverage(new BigDecimal("8.2"))
                .posterPath("/matrix.jpg"));
        return movie;
    }

    @Test
    void returnsPagedSummaryList() throws Exception {
        Page<Movie> page = new PageImpl<>(List.of(sampleMovie()), Pageable.ofSize(20), 1);
        given(movieService.search(any(), any())).willReturn(page);

        mockMvc.perform(get("/movies").param("title", "matrix"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.content[0].id").value(603))
                .andExpect(jsonPath("$.content[0].title").value("The Matrix"))
                .andExpect(jsonPath("$.content[0].voteAverage").value(8.2))
                .andExpect(jsonPath("$.content[0].genres[0].name").value("Action"))
                // summary DTO must NOT leak the heavy detail fields
                .andExpect(jsonPath("$.content[0].overview").doesNotExist())
                // stable PagedModel envelope: metadata lives under $.page (not a flat $.totalElements)
                .andExpect(jsonPath("$.page.totalElements").value(1));
    }

    @Test
    void bindsFilterParamsOntoMovieFilter() throws Exception {
        Page<Movie> empty = new PageImpl<>(List.of(), Pageable.ofSize(20), 0);
        given(movieService.search(any(), any())).willReturn(empty);

        mockMvc.perform(get("/movies")
                        .param("title", "matrix")
                        .param("genreId", "28")
                        .param("language", "en")
                        .param("releaseYearFrom", "1990")
                        .param("minRating", "7.5"))
                .andExpect(status().isOk());

        ArgumentCaptor<MovieFilter> captor = ArgumentCaptor.forClass(MovieFilter.class);
        org.mockito.Mockito.verify(movieService).search(captor.capture(), any());
        MovieFilter bound = captor.getValue();
        org.assertj.core.api.Assertions.assertThat(bound.title()).isEqualTo("matrix");
        org.assertj.core.api.Assertions.assertThat(bound.genreId()).isEqualTo(28L);
        org.assertj.core.api.Assertions.assertThat(bound.language()).isEqualTo("en");
        org.assertj.core.api.Assertions.assertThat(bound.releaseYearFrom()).isEqualTo(1990);
        org.assertj.core.api.Assertions.assertThat(bound.minRating()).isEqualByComparingTo("7.5");
    }

    @Test
    void emptyQueryReturnsDefaultPage() throws Exception {
        Page<Movie> page = new PageImpl<>(List.of(sampleMovie()), Pageable.ofSize(20), 1);
        given(movieService.search(any(), any())).willReturn(page);

        mockMvc.perform(get("/movies"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray());
    }

    @Test
    void rejectsUnknownSortFieldAsValidationError() throws Exception {
        given(movieService.search(any(), any())).willThrow(
                new CatalogException(CatalogErrorCode.CATALOG_VALIDATION_ERROR, "Cannot sort by 'evil'."));

        mockMvc.perform(get("/movies").param("sort", "evil"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("CATALOG_VALIDATION_ERROR"));
    }

    @Test
    void rejectsOutOfRangeRatingAsValidationError() throws Exception {
        // minRating=99 violates @DecimalMax(10) on MovieFilter -> handled before the service is called
        mockMvc.perform(get("/movies").param("minRating", "99"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("CATALOG_VALIDATION_ERROR"));
    }
}
